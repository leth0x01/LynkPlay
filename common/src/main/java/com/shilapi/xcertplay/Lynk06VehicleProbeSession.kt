package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSignal
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface VehicleProbeScheduler {
    fun nowNanos(): Long
    fun wallTimeMillis(): Long
    fun schedule(delayMillis: Long, action: () -> Unit)
}

internal object SystemVehicleProbeScheduler : VehicleProbeScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vehicle-probe-watchdog").apply { isDaemon = true }
    }
    override fun nowNanos() = SystemClock.elapsedRealtimeNanos()
    override fun wallTimeMillis() = System.currentTimeMillis()
    override fun schedule(delayMillis: Long, action: () -> Unit) {
        executor.schedule(action, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }
}

internal interface VehicleProbeIo {
    val busy: Boolean
    fun reserve(owner: Any): Boolean
    fun release(owner: Any)
    fun submit(work: () -> Unit, idle: () -> Unit): Boolean
}

/** 不排队、不取消 Binder；生产进程始终只使用 ProcessVehicleProbeIo 的一个线程。 */
internal class SingleVehicleProbeIo(name: String) : VehicleProbeIo {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }
    @Volatile override var busy = false
        private set
    private var owner: Any? = null

    @Synchronized override fun reserve(owner: Any): Boolean {
        if (this.owner === owner) return true
        if (this.owner != null || busy) return false
        this.owner = owner
        return true
    }

    @Synchronized override fun release(owner: Any) {
        if (this.owner === owner && !busy) this.owner = null
    }

    @Synchronized override fun submit(work: () -> Unit, idle: () -> Unit): Boolean {
        if (busy) return false
        busy = true
        executor.execute {
            try { work() } finally {
                synchronized(this) { busy = false }
                idle()
            }
        }
        return true
    }

    // 仅供测试释放 latch 后回收线程，生产不依赖中断取消车辆调用。
    fun awaitIdleForTest(timeoutMillis: Long) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        do {
            val remaining = (end - System.nanoTime()).coerceAtLeast(1)
            executor.submit {}.get(remaining, TimeUnit.NANOSECONDS)
        } while (busy && System.nanoTime() < end)
    }
    fun closeForTest() { executor.shutdown() }
}

internal object ProcessVehicleProbeIo : VehicleProbeIo by SingleVehicleProbeIo("vehicle-probe-io")

/** 所有状态转换都在短临界区中进行；临界区内绝不调用车辆后端或等待 I/O。 */
internal class Lynk06VehicleProbeSession(
    private val scheduler: VehicleProbeScheduler,
    private val io: VehicleProbeIo,
    private val backendFactory: () -> Lynk06VehicleProbeBackend,
    private val gate: () -> String?,
    private val reportSink: (Lynk06VehicleProbeReport) -> Unit,
    private val onTerminal: (String) -> Unit = {}
) {
    private enum class Phase { CREATE, CONNECT, WAIT_CONNECTED, LIST, SUBSCRIBE, POLL, CLEANUP, RECONNECT }
    private class Run(val id: String, val start: Long, val wall: Long) {
        val store = VehicleProbeStore()
        val deadline = start + DURATION_NANOS
        var backend: Lynk06VehicleProbeBackend? = null
        var phase = Phase.CREATE
        var generation = 1L
        var operation = 0L
        var inFlight = false
        var status = "running"
        var reason = "starting"
        var connected = false
        var supported = emptySet<Int>()
        var index = 0
        var retries = 0
        var reconnectAt = 0L
        var nextPollAt = 0L
        var lastReportAt = start
        var cleanupError: String? = null
        var stoppedAt: Long? = null
        // 属性 id → 注册返回值；失败或抛出异常记 false，便于区分是否值得依赖回调。
        val subscriptions = LinkedHashMap<Int, Boolean>()
    }
    private var current: Run? = null
    private var destroyed = false
    private val signals = VehicleProbeSignal.entries.toList()

    @Synchronized fun start(requestId: String) {
        if (!VehicleProbeRequestIds.isCanonical(requestId)) return
        val old = current
        // 相同请求不重写终态，也不移动原截止时间。
        if (old?.id == requestId) return
        val run = Run(requestId, scheduler.nowNanos(), scheduler.wallTimeMillis())
        val refusal = when {
            destroyed -> "service_destroyed"
            old != null && (old.status == "running" || cleanupPending(old)) -> "busy"
            io.busy -> "busy"
            else -> try { gate() } catch (_: Exception) { "gate_failed" }
        } ?: if (!io.reserve(run)) "busy" else null
        if (refusal != null) {
            val store = VehicleProbeStore().apply { stop(refusal) }
            reportSink(Lynk06VehicleProbeReport(requestId, "rejected", refusal, scheduler.wallTimeMillis(),
                0, store.snapshot(scheduler.nowNanos()), false))
            return
        }
        current = run
        emit(run)
        scheduler.schedule(60_000) { synchronized(this) { if (isRunning(run)) finish(run, "completed", "deadline") } }
        heartbeat(run)
        drive(run)
    }

    @Synchronized fun stop(requestId: String) {
        val run = current ?: return
        if (run.id == requestId && isRunning(run)) finish(run, "stopped", "requested_stop")
    }

    @Synchronized fun destroy() {
        destroyed = true
        current?.takeIf { isRunning(it) }?.let { finish(it, "stopped", "service_destroyed") }
    }

    @Synchronized fun isRunningRequest(): Boolean = current?.status == "running"

    /** 供 Service 去重集保护当前活动请求，不泄露内部 Run。 */
    @Synchronized fun currentRequestId(): String? = current?.takeIf { it.status == "running" }?.id

    private fun isRunning(run: Run) = current === run && run.status == "running"
    private fun cleanupPending(run: Run) = run.backend != null || run.inFlight

    private fun heartbeat(run: Run) {
        scheduler.schedule(1_000) {
            synchronized(this) {
                if (!isRunning(run)) return@synchronized
                if (scheduler.nowNanos() >= run.deadline) {
                    finish(run, "completed", "deadline")
                } else {
                    if (scheduler.nowNanos() - run.lastReportAt >= 1_000_000_000L) emit(run)
                    drive(run)
                    heartbeat(run)
                }
            }
        }
    }

    private fun emit(run: Run) {
        val now = run.stoppedAt ?: scheduler.nowNanos()
        run.lastReportAt = scheduler.nowNanos()
        reportSink(Lynk06VehicleProbeReport(run.id, run.status, run.reason, run.wall,
            ((now - run.start) / 1_000_000).coerceAtLeast(0), run.store.snapshot(scheduler.nowNanos()),
            cleanupPending(run), run.cleanupError,
            synchronized(this) {
                run.subscriptions.map { (id, registered) -> VehicleProbeSubscription(id, registered) }
            }))
    }

    private fun finish(run: Run, status: String, reason: String) {
        if (!isRunning(run)) return
        run.status = status
        run.reason = reason
        run.stoppedAt = scheduler.nowNanos()
        run.generation++
        run.connected = false
        run.store.stop(reason)
        run.phase = Phase.CLEANUP
        emit(run)
        // 通知和报告都不等待车辆清理；Service 可立即退出。
        onTerminal(run.id)
        drive(run)
    }

    private fun disconnected(run: Run, generation: Long) = synchronized(this) {
        if (!valid(run, generation)) return@synchronized
        run.generation++
        run.connected = false
        run.store.markDisconnected()
        run.phase = Phase.CLEANUP
        drive(run)
    }

    private fun valid(run: Run, generation: Long): Boolean {
        if (!isRunning(run) || run.generation != generation) return false
        if (scheduler.nowNanos() >= run.deadline) {
            finish(run, "completed", "deadline")
            return false
        }
        return true
    }

    private fun drive(run: Run) {
        if (current !== run || run.inFlight || io.busy) return
        if (isRunning(run) && scheduler.nowNanos() >= run.deadline) {
            finish(run, "completed", "deadline")
            return
        }
        if (run.phase == Phase.CLEANUP || !isRunning(run)) {
            val backend = run.backend
            if (backend == null) { afterCleanup(run); return }
            operation(run, "close", cleanup = true, work = {
                backend.close()
            }, success = {
                run.backend = null
                afterCleanup(run)
            }, failure = {
                run.backend = null
                run.cleanupError = "cleanup_failed"
                afterCleanup(run)
            })
            return
        }
        val generation = run.generation
        when (run.phase) {
            Phase.CREATE -> operation(run, "create", work = {
                val backend = backendFactory()
                // 即使 create 晚到，也保存引用以便在同一 I/O 槽中清理。
                synchronized(this) { run.backend = backend }
            }, success = { run.phase = Phase.CONNECT })
            Phase.CONNECT -> {
                val backend = run.backend ?: return
                run.phase = Phase.WAIT_CONNECTED
                operation(run, "connect", work = {
                    backend.connect(onConnected = {
                        synchronized(this) {
                            if (valid(run, generation) && !run.connected) {
                                run.connected = true
                                run.store.markConnected()
                                run.phase = Phase.LIST
                                drive(run)
                            }
                        }
                    }, onDisconnected = { disconnected(run, generation) })
                }, success = {
                    if (!run.connected && run.phase == Phase.WAIT_CONNECTED) {
                        scheduler.schedule(3_000) {
                            synchronized(this) {
                                if (valid(run, generation) && !run.connected && run.phase == Phase.WAIT_CONNECTED)
                                    finish(run, "failed", "connect_timeout")
                            }
                        }
                    }
                })
            }
            Phase.LIST -> {
                val backend = run.backend ?: return
                var supported = emptySet<Int>()
                operation(run, "list", work = { supported = backend.supportedPropertyIds() }, success = {
                    run.supported = supported.intersect(signals.map { it.propertyId }.toSet())
                    signals.filter { it.propertyId !in run.supported }.forEach { run.store.markUnsupported(it) }
                    run.index = 0
                    run.phase = Phase.SUBSCRIBE
                })
            }
            Phase.SUBSCRIBE -> {
                while (run.index < signals.size && signals[run.index].propertyId !in run.supported) run.index++
                if (run.index == signals.size) {
                    run.phase = Phase.POLL; run.index = 0; drive(run); return
                }
                val signal = signals[run.index++]
                val backend = run.backend ?: return
                operation(run, "subscribe", work = {
                    val registered = backend.subscribe(signal.propertyId) { observation ->
                        // 回调直接合入有界模型，不为每条回调排任务或写盘。
                        synchronized(this) {
                            if (valid(run, generation) && run.connected && observation.propertyId == signal.propertyId)
                                run.store.accept(observation)
                        }
                    }
                    synchronized(this) { run.subscriptions[signal.propertyId] = registered }
                }, success = {})
            }
            Phase.POLL -> {
                if (scheduler.nowNanos() < run.nextPollAt) return
                while (run.index < signals.size && signals[run.index].propertyId !in run.supported) run.index++
                if (run.index == signals.size) {
                    run.index = 0
                    run.nextPollAt = scheduler.nowNanos() + 1_000_000_000L
                    scheduler.schedule(1_000) { synchronized(this) { if (isRunning(run)) drive(run) } }
                    return
                }
                val signal = signals[run.index++]
                val backend = run.backend ?: return
                operation(run, "read", work = {
                    val observation = backend.read(signal.propertyId)
                    synchronized(this) {
                        if (valid(run, generation) && run.connected && observation.propertyId == signal.propertyId)
                            run.store.accept(observation)
                    }
                }, success = {})
            }
            Phase.RECONNECT -> if (scheduler.nowNanos() >= run.reconnectAt) {
                run.phase = Phase.CREATE; drive(run)
            }
            else -> Unit
        }
    }

    private fun afterCleanup(run: Run) {
        if (!isRunning(run)) {
            if (!cleanupPending(run) && !io.busy) io.release(run)
            emit(run)
            return
        }
        if (run.retries >= 4) { finish(run, "failed", "reconnect_exhausted"); return }
        val delay = 1_000L shl run.retries++
        run.generation++
        run.phase = Phase.RECONNECT
        run.reconnectAt = scheduler.nowNanos() + delay * 1_000_000
        scheduler.schedule(delay) { synchronized(this) { if (isRunning(run)) drive(run) } }
    }

    private fun operation(
        run: Run,
        stage: String,
        cleanup: Boolean = false,
        work: () -> Unit,
        success: () -> Unit,
        failure: (() -> Unit)? = null
    ) {
        val generation = run.generation
        val token = ++run.operation
        run.inFlight = true
        scheduler.schedule(3_000) {
            synchronized(this) {
                if (current === run && run.inFlight && run.operation == token) {
                    if (cleanup) {
                        run.cleanupError = "cleanup_timeout"
                        if (isRunning(run)) finish(run, "failed", "close_timeout") else emit(run)
                    } else if (isRunning(run)) finish(run, "failed", "${stage}_timeout")
                }
            }
        }
        val accepted = io.submit(work = {
            var error: Throwable? = null
            try { work() } catch (caught: Throwable) { error = caught }
            synchronized(this) {
                run.inFlight = false
                if (cleanup) {
                    if (error == null) success() else failure?.invoke()
                } else if (valid(run, generation)) {
                    if (error == null) success() else finish(run, "failed", "${stage}_failed")
                }
            }
        }, idle = { synchronized(this) { drive(run) } })
        if (!accepted) run.inFlight = false
    }

    companion object { private const val DURATION_NANOS = 60_000_000_000L }
}
