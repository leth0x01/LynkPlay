package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.probe.*
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Lynk06VehicleProbeSessionTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val other = "22345678-1234-1234-1234-123456789abc"

    private class Time : VehicleProbeScheduler {
        var now = 0L
        private var order = 0L
        private data class Task(val at: Long, val order: Long, val action: () -> Unit)
        private val tasks = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.order })
        override fun nowNanos() = now
        override fun wallTimeMillis() = 123L
        @Synchronized override fun schedule(delayMillis: Long, action: () -> Unit) {
            tasks.add(Task(now + delayMillis * 1_000_000, order++, action))
        }
        fun advance(millis: Long) {
            val end = now + millis * 1_000_000
            while (true) {
                val task = synchronized(this) { if (tasks.peek()?.at?.let { it <= end } == true) tasks.remove() else null } ?: break
                now = task.at
                task.action()
            }
            now = end
        }
    }

    private open class Fake : Lynk06VehicleProbeBackend {
        var connected: (() -> Unit)? = null
        var disconnected: (() -> Unit)? = null
        val callbacks = mutableListOf<(VehicleProbeObservation) -> Unit>()
        var closes = 0
        override fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit) {
            connected = onConnected; disconnected = onDisconnected; onConnected()
        }
        override fun supportedPropertyIds() = VehicleProbeSignal.entries.map { it.propertyId }.toSet()
        override fun read(propertyId: Int) = VehicleProbeObservation(propertyId, 0, 0, 0, 0,
            ByteArray(VehicleProbeSignal.entries.first { it.propertyId == propertyId }.byteCount), VehicleProbeSource.POLL)
        override fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean {
            callbacks.add(callback); return true
        }
        override fun close() { closes++ }
    }

    private class ImmediateIo : VehicleProbeIo {
        override var busy = false
        private var owner: Any? = null
        override fun reserve(owner: Any): Boolean {
            if (this.owner != null && this.owner !== owner) return false
            this.owner = owner; return true
        }
        override fun release(owner: Any) { if (this.owner === owner && !busy) this.owner = null }
        override fun submit(work: () -> Unit, idle: () -> Unit): Boolean {
            if (busy) return false
            busy = true
            try { work() } finally { busy = false }
            idle()
            return true
        }
    }

    @Test fun deadlineDuplicateBusyAndStopAreRequestScoped() {
        val clock = Time(); val fake = Fake(); val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val session = Lynk06VehicleProbeSession(clock, ImmediateIo(), { fake }, { null }, reports::add)
        session.start(id)
        clock.advance(30_000)
        session.start(id)
        session.start(other)
        assertEquals("rejected", reports.last { it.requestId == other }.status)
        session.stop(other)
        clock.advance(30_000)
        assertEquals("completed", reports.last { it.requestId == id }.status)
        assertTrue(reports.last { it.requestId == id }.snapshot.stopped)
        assertEquals(60_000L, reports.last { it.requestId == id }.elapsedMillis)
        assertEquals(1, fake.closes)
    }

    @Test fun reportRecordsSubscriptionOutcomePerProperty() {
        // 实车回调为 0 时，必须能区分“未注册”与“已注册但无事件”。
        val clock = Time(); val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val fake = object : Fake() {
            override fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean {
                super.subscribe(propertyId, callback)
                return propertyId != VehicleProbeSignal.FUEL_RAW.propertyId
            }
        }
        val session = Lynk06VehicleProbeSession(clock, ImmediateIo(), { fake }, { null }, reports::add)
        session.start(id)
        clock.advance(60_000)
        val subscriptions = reports.last().subscriptions.associate { it.propertyId to it.registered }
        assertEquals(VehicleProbeSignal.values().size, subscriptions.size)
        assertEquals(false, subscriptions[VehicleProbeSignal.FUEL_RAW.propertyId])
        assertTrue(subscriptions.filterKeys { it != VehicleProbeSignal.FUEL_RAW.propertyId }.values.all { it })
    }

    @Test fun gateAndInvalidIdNeverConstructBackend() {
        val clock = Time(); var creates = 0; val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val session = Lynk06VehicleProbeSession(clock, ImmediateIo(), { creates++; Fake() }, { "permission_denied" }, reports::add)
        session.start("../bad")
        session.start(id)
        assertEquals(0, creates)
        assertEquals(1, reports.size)
        assertEquals("permission_denied", reports.single().reason)
        assertEquals("rejected", reports.single().status)
    }

    @Test fun destroyInvalidatesAndCleanupExceptionsDoNotEscape() {
        val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val fake = object : Fake() { override fun close() { super.close(); error("private details") } }
        val session = Lynk06VehicleProbeSession(Time(), ImmediateIo(), { fake }, { null }, reports::add)
        session.start(id)
        session.destroy()
        assertEquals("stopped", reports.last().status)
        assertEquals("service_destroyed", reports.last().reason)
        assertFalse(reports.last().cleanupPending)
        assertEquals("cleanup_failed", reports.last().cleanupError)
    }

    @Test fun reconnectDiscardsOldCallbacksAndKeepsOriginalDeadline() {
        val clock = Time(); val backends = mutableListOf<Fake>(); val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val session = Lynk06VehicleProbeSession(clock, ImmediateIo(), { Fake().also { backends.add(it) } }, { null }, reports::add)
        session.start(id)
        val old = backends.single()
        val callback = old.callbacks.first()
        old.disconnected!!()
        clock.advance(1_000)
        assertEquals(2, backends.size)
        callback(VehicleProbeObservation(VehicleProbeSignal.ODOMETER.propertyId, 0, 0, 0, clock.now, byteArrayOf(1, 0, 0, 0), VehicleProbeSource.CALLBACK))
        clock.advance(1_000)
        assertEquals(0L, reports.last().snapshot.readings.first().callbackCount)
        clock.advance(58_000)
        assertEquals("completed", reports.last().status)
    }

    @Test fun reconnectBackoffIsBoundedToOneTwoFourEightSeconds() {
        val clock = Time(); val backends = mutableListOf<Fake>(); val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val session = Lynk06VehicleProbeSession(clock, ImmediateIo(), { Fake().also { backends.add(it) } }, { null }, reports::add)
        session.start(id)
        listOf(1_000L, 2_000L, 4_000L, 8_000L).forEachIndexed { index, delay ->
            backends.last().disconnected!!()
            clock.advance(delay - 1)
            assertEquals(index + 1, backends.size)
            clock.advance(1)
            assertEquals(index + 2, backends.size)
        }
        backends.last().disconnected!!()
        assertEquals("failed", reports.last().status)
        assertEquals("reconnect_exhausted", reports.last().reason)
        assertEquals(5, backends.sumOf { it.closes })
    }

    @Test fun unexpectedLinkageErrorsAreStageFailuresNotAnOrphanedIoSlot() {
        val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val io = ImmediateIo()
        val session = Lynk06VehicleProbeSession(Time(), io, { throw NoClassDefFoundError("private") }, { null }, reports::add)
        session.start(id)
        assertEquals("failed", reports.last().status)
        assertEquals("create_failed", reports.last().reason)
        assertFalse(reports.last().cleanupPending)
        assertTrue(io.reserve(Any()))
    }

    @Test(timeout = 5_000) fun allVehicleCallsIncludingCleanupStayOnTheSingleIoThread() {
        val threads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val ready = CountDownLatch(1)
        val io = SingleVehicleProbeIo("probe-thread-test")
        val fake = object : Fake() {
            override fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit) {
                threads.add(Thread.currentThread().name); super.connect(onConnected, onDisconnected)
            }
            override fun supportedPropertyIds(): Set<Int> {
                threads.add(Thread.currentThread().name); return super.supportedPropertyIds()
            }
            override fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean {
                threads.add(Thread.currentThread().name); return super.subscribe(propertyId, callback)
            }
            override fun read(propertyId: Int): VehicleProbeObservation {
                threads.add(Thread.currentThread().name); ready.countDown(); return super.read(propertyId)
            }
            override fun close() { threads.add(Thread.currentThread().name); super.close() }
        }
        val session = Lynk06VehicleProbeSession(Time(), io, {
            threads.add(Thread.currentThread().name); fake
        }, { null }, {})
        try {
            session.start(id)
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            session.stop(id)
            io.awaitIdleForTest(1_000)
            assertEquals(setOf("probe-thread-test"), threads.toSet())
            assertEquals(1, fake.closes)
        } finally {
            session.destroy(); io.awaitIdleForTest(1_000); io.closeForTest()
        }
    }

    @Test(timeout = 5_000) fun blockedIoCannotBlockStopReportsOrCreateASecondWorker() {
        val clock = Time(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reports = java.util.Collections.synchronizedList(mutableListOf<Lynk06VehicleProbeReport>())
        val io = SingleVehicleProbeIo("probe-test-io")
        var creates = 0
        val session = Lynk06VehicleProbeSession(clock, io, {
            creates++; entered.countDown(); release.await(); Fake()
        }, { null }, { reports.add(it) })
        try {
            session.start(id)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            clock.advance(3_000)
            assertEquals("failed", reports.last().status)
            assertTrue(reports.last().cleanupPending)
            session.start(other)
            assertEquals("rejected", reports.last().status)
            assertEquals(1, creates)
            assertTrue(io.busy)
        } finally {
            release.countDown()
            io.awaitIdleForTest(1_000)
            io.closeForTest()
        }
    }

    @Test fun processLeaseSurvivesIdleGapsAndServiceReplacementUntilCleanup() {
        val clock = Time(); val io = ImmediateIo(); var creates = 0
        val reports = mutableListOf<Lynk06VehicleProbeReport>()
        val first = Lynk06VehicleProbeSession(clock, io, { creates++; Fake() }, { null }, reports::add)
        val second = Lynk06VehicleProbeSession(clock, io, { creates++; Fake() }, { null }, reports::add)
        first.start(id)
        // 等待下一轮轮询时物理 I/O 空闲，但另一 Service 实例不能夺走旧请求的租约。
        assertFalse(io.busy)
        second.start(other)
        assertEquals("busy", reports.last().reason)
        assertEquals(1, creates)
        first.destroy()
        second.start(other)
        assertEquals(2, creates)
        second.destroy()
    }

    @Test(timeout = 5_000) fun explicitStopWhileBinderBlockedWritesWithoutWaiting() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reports = java.util.Collections.synchronizedList(mutableListOf<Lynk06VehicleProbeReport>())
        val io = SingleVehicleProbeIo("probe-stop-test")
        val session = Lynk06VehicleProbeSession(Time(), io, {
            entered.countDown(); release.await(); Fake()
        }, { null }, { reports.add(it) })
        try {
            session.start(id)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            session.stop(id)
            assertEquals("stopped", reports.last().status)
            assertTrue(reports.last().cleanupPending)
        } finally {
            release.countDown(); io.awaitIdleForTest(1_000); io.closeForTest()
        }
    }
}
