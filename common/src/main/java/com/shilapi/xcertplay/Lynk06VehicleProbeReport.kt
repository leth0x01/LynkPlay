package com.shilapi.xcertplay

import android.util.AtomicFile
import android.util.JsonWriter
import android.util.Log
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeReading
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSignal
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSnapshot
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeValueState
import java.io.File
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.Executors

internal object VehicleProbeRequestIds {
    fun isCanonical(value: String?): Boolean = value != null && value.length == 36 &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

internal data class VehicleProbeSubscription(val propertyId: Int, val registered: Boolean)

internal data class Lynk06VehicleProbeReport(
    val requestId: String,
    val status: String,
    val reason: String,
    val startedWallTimeMillis: Long,
    val elapsedMillis: Long,
    val snapshot: VehicleProbeSnapshot,
    val cleanupPending: Boolean,
    val cleanupError: String? = null,
    /** 每个已尝试属性的注册结果；区分“注册失败”与“注册成功但无回调”。 */
    val subscriptions: List<VehicleProbeSubscription> = emptyList()
) {
    fun toJson(maxBytes: Int = MAX_BYTES): String {
        require(VehicleProbeRequestIds.isCanonical(requestId))
        require(status in setOf("running", "completed", "stopped", "failed", "rejected"))
        require(maxBytes in 1..MAX_BYTES)
        var history = snapshot.history.takeLast(256)
        var dropped = snapshot.droppedEvents + snapshot.history.size - history.size
        while (true) {
            val text = StringWriter()
            JsonWriter(text).use { json ->
                json.beginObject()
                json.name("schemaVersion").value(1)
                json.name("requestId").value(requestId)
                json.name("readOnly").value(true)
                json.name("status").value(status)
                json.name("reason").value(code(reason))
                json.name("startedWallTimeMillis").value(startedWallTimeMillis)
                json.name("elapsedMillis").value(elapsedMillis)
                json.name("freshnessBasis").value("local_observation")
                json.name("connected").value(snapshot.connected)
                json.name("stopped").value(snapshot.stopped)
                json.name("cleanupPending").value(cleanupPending)
                json.name("cleanupError").value(code(cleanupError))
                json.name("readings").beginArray()
                snapshot.readings.forEach { writeReading(json, it) }
                json.endArray()
                json.name("history").beginArray()
                history.forEach {
                    json.beginObject()
                    json.name("source").value(it.source.name)
                    json.name("receivedElapsedNanos").value(it.receivedElapsedNanos)
                    json.name("reading")
                    writeReading(json, it.reading)
                    json.endObject()
                }
                json.endArray()
                json.name("droppedEvents").value(dropped)
                json.name("subscriptions").beginArray()
                subscriptions.forEach { subscription ->
                    val callbacks = snapshot.readings.firstOrNull { it.signal.propertyId == subscription.propertyId }
                        ?.callbackCount ?: 0L
                    json.beginObject()
                    json.name("propertyId").value("0x%08x".format(java.util.Locale.ROOT, subscription.propertyId))
                    json.name("registered").value(subscription.registered)
                    json.name("callbacks").value(callbacks)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
            val encoded = text.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size <= maxBytes) return encoded
            check(history.isNotEmpty()) { "report_size_limit" }
            // 只丢弃最旧历史，不截断 JSON，也不改变当前读数。
            val remove = maxOf(1, history.size / 4)
            history = history.drop(remove)
            dropped += remove
        }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        private fun code(value: String?): String? = value?.take(64)?.replace(Regex("[^a-zA-Z0-9_./-]"), "_")
        private fun writeReading(json: JsonWriter, reading: VehicleProbeReading) {
            val candidate = reading.signal == VehicleProbeSignal.RPM_RAW || reading.signal == VehicleProbeSignal.FUEL_RAW
            val decoded = !candidate && reading.state == VehicleProbeValueState.AVAILABLE
            json.beginObject()
            json.name("key").value(reading.signal.key)
            json.name("propertyId").value("0x%08x".format(java.util.Locale.ROOT, reading.signal.propertyId))
            json.name("state").value(reading.state.name)
            json.name("rawHex").value(reading.rawHex)
            json.name("rawUnsigned").value(reading.rawUnsigned)
            json.name("numericValue").value(if (decoded) reading.numericValue else null)
            json.name("textValue").value(if (decoded) reading.textValue else null)
            json.name("unit").value(if (decoded) reading.unit else null)
            json.name("sourceStatus").value(reading.sourceStatus)
            json.name("sourceTimestampNanos").value(reading.sourceTimestampNanos)
            json.name("observedElapsedNanos").value(reading.observedElapsedNanos)
            json.name("ageMillis").value(reading.ageMillis)
            json.name("readCount").value(reading.readCount)
            json.name("callbackCount").value(reading.callbackCount)
            json.name("errorCode").value(code(reading.errorCode))
            json.endObject()
        }
    }
}

/** 只由报告线程调用；目录与文件名均由程序固定生成。 */
internal class AtomicVehicleProbeReportSink(private val externalFilesDir: File) {
    companion object { private const val RETAIN_REPORTS = 5 }
    fun write(report: Lynk06VehicleProbeReport) {
        require(VehicleProbeRequestIds.isCanonical(report.requestId))
        val directory = File(externalFilesDir, "vehicle-probe")
        check(directory.isDirectory || directory.mkdirs()) { "report_directory_failed" }
        val canonicalDirectory = File(externalFilesDir.canonicalFile, "vehicle-probe")
        check(directory.canonicalFile == canonicalDirectory) { "report_directory_symlink" }
        val target = File(directory, "${report.requestId}.json")
        listOf(target, File(target.path + ".bak"), File(target.path + ".new")).forEach {
            check(it.canonicalFile == File(canonicalDirectory, it.name)) { "report_file_symlink" }
        }
        val atomic = AtomicFile(target)
        val bytes = report.toJson().toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        val reports = directory.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".json") &&
                VehicleProbeRequestIds.isCanonical(it.name.removeSuffix(".json")) && it.canonicalFile == File(canonicalDirectory, it.name)
        }
        // 正在运行或清理未完成的报告不能因拒绝报告过多而被淘汰，否则本轮结果无法拉回。
        val candidates = reports.filterNot { protectedReport(it, target) }.sortedBy { it.lastModified() }
        val excess = reports.size - RETAIN_REPORTS
        if (excess > 0) candidates.take(excess).forEach { check(it.delete()) { "report_retention_failed" } }
    }

    private fun protectedReport(file: File, target: File): Boolean {
        if (file == target) return true
        return runCatching {
            val root = org.json.JSONObject(file.readText(Charsets.UTF_8))
            root.optString("status") == "running" || root.optBoolean("cleanupPending", false)
        }.getOrDefault(false)
    }
}

/** 与车辆 I/O、看门狗隔离的进程级单写者；同一请求尚未写出的快照自动合并。 */
internal object VehicleProbeReportWriter {
    private const val MAX_PENDING = 32
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vehicle-probe-report").apply { isDaemon = true }
    }
    private val pending = LinkedHashMap<String, Pair<AtomicVehicleProbeReportSink, Lynk06VehicleProbeReport>>()
    private var draining = false
    @Volatile internal var droppedPending = 0L
        private set

    internal fun awaitIdleForTest() {
        executor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized fun enqueue(sink: AtomicVehicleProbeReportSink, report: Lynk06VehicleProbeReport) {
        pending[report.requestId] = sink to report
        if (pending.size > MAX_PENDING) {
            // 写盘阻塞时不同 UUID 可能持续堆积；优先丢弃旧的拒绝报告，绝不动运行中或待清理的终态。
            val victim = pending.entries.firstOrNull {
                it.key != report.requestId && it.value.second.status != "running" && !it.value.second.cleanupPending
            }?.key ?: pending.keys.firstOrNull { it != report.requestId }
            if (victim != null) {
                pending.remove(victim)
                droppedPending++
            }
        }
        if (draining) return
        draining = true
        executor.execute {
            while (true) {
                val entry = synchronized(this) {
                    val key = pending.keys.firstOrNull()
                    if (key == null) { draining = false; null } else pending.remove(key)
                } ?: break
                try {
                    entry.first.write(entry.second)
                } catch (error: Exception) {
                    // 不输出可能包含车辆数据或路径的异常消息，也不记录虚假的完成日志。
                    Log.e("VehicleProbe", "报告写入失败：${error.javaClass.simpleName.take(48)}")
                }
            }
        }
    }
}
