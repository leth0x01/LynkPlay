package com.shilapi.xcertplay.vehicle.probe

import java.util.ArrayDeque

/**
 * 仅处理不可变诊断值，不依赖 android.car。所有入口使用同一把锁。
 * 连接代次由会话层隔离；重连后必须由会话层拦截旧后端的迟到回调。
 */
class VehicleProbeStore(
    historyLimit: Int = 256,
    private val staleAfterNanos: Long = 3_000_000_000L,
) {
    private class Entry(val signal: VehicleProbeSignal) {
        var reading = emptyReading(signal)
        var lastPositiveTimestamp: Long? = null
    }

    private val historyLimit = historyLimit.coerceAtMost(256)
    private val entries = VehicleProbeSignal.values().map { Entry(it) }
    private val history = ArrayDeque<VehicleProbeEvent>()
    private var droppedEvents = 0L
    private var connected = false
    private var stopped = false
    private var lastReceivedElapsedNanos: Long? = null

    init {
        require(historyLimit >= 0) { "history_limit" }
        require(staleAfterNanos > 0) { "stale_after" }
    }

    @Synchronized
    fun markConnected() {
        if (stopped) return
        connected = true
        lastReceivedElapsedNanos = null
        entries.forEach { entry ->
            val previous = entry.reading
            entry.reading = emptyReading(entry.signal).copy(
                readCount = previous.readCount,
                callbackCount = previous.callbackCount,
            )
            entry.lastPositiveTimestamp = null
        }
    }

    @Synchronized
    fun markDisconnected(errorCode: String = "disconnected") {
        if (stopped) return
        connected = false
        entries.forEach { it.reading = it.reading.invalidated(VehicleProbeValueState.DISCONNECTED, errorCode) }
    }

    @Synchronized
    fun markUnsupported(signal: VehicleProbeSignal, errorCode: String = "unsupported") {
        if (stopped) return
        val entry = entries[signal.ordinal]
        entry.reading = entry.reading.invalidated(VehicleProbeValueState.UNSUPPORTED, errorCode)
    }

    @Synchronized
    fun markError(signal: VehicleProbeSignal, errorCode: String) {
        if (stopped) return
        val entry = entries[signal.ordinal]
        entry.reading = entry.reading.invalidated(VehicleProbeValueState.ERROR, errorCode)
    }

    /**
     * 无权限进入当前连接或源时间倒序时返回 false。
     * 已识别的错误观察仍计数并留历史；倒序观察不覆盖当前值或刷新其新鲜度。
     */
    @Synchronized
    fun accept(observation: VehicleProbeObservation): Boolean {
        if (!connected || stopped || observation.areaId != 0) return false
        val entry = entries.firstOrNull { it.signal.propertyId == observation.propertyId } ?: return false
        val previous = entry.reading
        val poll = observation.source == VehicleProbeSource.POLL
        val counted = previous.copy(
            readCount = previous.readCount + if (poll) 1 else 0,
            callbackCount = previous.callbackCount + if (poll) 0 else 1,
        )
        // 只复制白名单规定的至多四字节，畸形输入不能把大数组编码进历史。
        val providedBytes = observation.rawBytes
        val validLength = providedBytes?.size == entry.signal.byteCount
        val bytes = providedBytes?.takeIf { validLength }?.copyOf()
        val rawUnsigned = bytes?.foldIndexed(0L) { index, value, byte ->
            value or ((byte.toLong() and 0xffL) shl (index * 8))
        }
        val received = observation.receivedElapsedNanos
        val timestamp = observation.sourceTimestampNanos
        val clockError = if (received < 0) "negative_local_time" else null
        // Binder 回调与轮询交付可能乱序；最高接收时刻只用于检查快照时钟。
        if (received >= 0) lastReceivedElapsedNanos = maxOf(lastReceivedElapsedNanos ?: received, received)
        val timestampError = when {
            timestamp < 0 -> "negative_timestamp"
            // 先比较大小再相减，避免接近 Long.MAX_VALUE 时加法溢出。
            received >= 0 && timestamp > received && timestamp - received > FUTURE_TOLERANCE_NANOS -> "future_timestamp"
            else -> null
        }
        val outOfOrder = timestamp > 0 && entry.lastPositiveTimestamp?.let { timestamp < it } == true
        val outOfOrderLocal = received >= 0 && previous.observedElapsedNanos?.let { received < it } == true
        var reading = VehicleProbeReading(
            signal = entry.signal,
            state = VehicleProbeValueState.ERROR,
            rawHex = bytes?.toHex(),
            rawUnsigned = rawUnsigned,
            numericValue = null,
            textValue = null,
            unit = null,
            sourceStatus = observation.status,
            sourceTimestampNanos = timestamp,
            observedElapsedNanos = received,
            ageMillis = if (received >= 0) 0L else null,
            readCount = counted.readCount,
            callbackCount = counted.callbackCount,
            errorCode = null,
        )
        val error = clockError ?: timestampError ?: when {
            outOfOrderLocal -> "out_of_order_local"
            outOfOrder -> "out_of_order"
            observation.errorCode != null -> observation.errorCode
            observation.status == 1 -> "status_unavailable"
            observation.status != 0 -> "status_error"
            providedBytes == null -> "missing_value"
            !validLength -> "length_mismatch"
            bytes!!.all { it == 0xff.toByte() } -> "all_ff"
            entry.signal == VehicleProbeSignal.GEAR && rawUnsigned !in GEAR_NAMES -> "unknown_gear"
            else -> null
        }
        reading = if (error != null) {
            reading.copy(
                state = if (clockError == null && timestampError == null && !outOfOrder && !outOfOrderLocal &&
                    observation.errorCode == null && observation.status == 1
                ) VehicleProbeValueState.UNAVAILABLE else VehicleProbeValueState.ERROR,
                errorCode = error,
            )
        } else {
            decode(reading)
        }
        appendEvent(VehicleProbeEvent(reading, observation.source, received))
        if (outOfOrderLocal || (outOfOrder && clockError == null && timestampError == null)) {
            entry.reading = counted
            return false
        }
        entry.reading = reading
        if (reading.isUsable() && timestamp > 0) entry.lastPositiveTimestamp = timestamp
        return true
    }

    @Synchronized
    fun stop(reason: String = "stopped") {
        if (stopped) return
        stopped = true
        connected = false
        entries.forEach { it.reading = it.reading.invalidated(VehicleProbeValueState.STOPPED, reason) }
    }

    @Synchronized
    fun snapshot(nowElapsedNanos: Long): VehicleProbeSnapshot {
        val readings = entries.map { entry ->
            var reading = entry.reading
            val observed = reading.observedElapsedNanos
            val clockRegressed = nowElapsedNanos < 0 ||
                lastReceivedElapsedNanos?.let { nowElapsedNanos < it } == true
            val age = if (observed != null && observed >= 0 && nowElapsedNanos >= observed) {
                nowElapsedNanos - observed
            } else null
            if (reading.isUsable()) {
                reading = when {
                    clockRegressed -> reading.invalidated(VehicleProbeValueState.ERROR, "clock_regressed")
                    age != null && age >= staleAfterNanos -> reading.invalidated(VehicleProbeValueState.STALE, "stale")
                    else -> reading
                }
            }
            // 快照只计算年龄和失效，不改变本地成功观察时间，也不写入观察历史。
            entry.reading = reading
            reading.copy(ageMillis = age?.div(1_000_000L))
        }
        return VehicleProbeSnapshot(connected, stopped, readings, history.toList(), droppedEvents)
    }

    private fun appendEvent(event: VehicleProbeEvent) {
        if (historyLimit == 0) {
            droppedEvents++
            return
        }
        if (history.size == historyLimit) {
            history.removeFirst()
            droppedEvents++
        }
        history.addLast(event)
    }

    private fun decode(reading: VehicleProbeReading): VehicleProbeReading {
        val raw = checkNotNull(reading.rawUnsigned)
        return when (reading.signal) {
            VehicleProbeSignal.RPM_RAW, VehicleProbeSignal.FUEL_RAW ->
                reading.copy(state = VehicleProbeValueState.RAW_ONLY)
            VehicleProbeSignal.GEAR ->
                reading.copy(state = VehicleProbeValueState.AVAILABLE, textValue = GEAR_NAMES[raw])
            else -> reading.copy(
                state = VehicleProbeValueState.AVAILABLE,
                numericValue = when (reading.signal) {
                    VehicleProbeSignal.ODOMETER -> raw * 0.1
                    VehicleProbeSignal.SPEED -> raw * 0.01
                    else -> raw.toDouble()
                },
                unit = reading.signal.unit,
            )
        }
    }

    private companion object {
        const val FUTURE_TOLERANCE_NANOS = 1_000_000_000L
        const val HEX_DIGITS = "0123456789ABCDEF"
        val GEAR_NAMES = mapOf(2L to "D", 3L to "N", 4L to "R", 5L to "P")

        fun emptyReading(signal: VehicleProbeSignal) = VehicleProbeReading(
            signal = signal,
            state = VehicleProbeValueState.WAITING,
            rawHex = null,
            rawUnsigned = null,
            numericValue = null,
            textValue = null,
            unit = null,
            sourceStatus = null,
            sourceTimestampNanos = null,
            observedElapsedNanos = null,
            ageMillis = null,
            readCount = 0,
            callbackCount = 0,
            errorCode = null,
        )

        fun VehicleProbeReading.isUsable() = state == VehicleProbeValueState.AVAILABLE ||
            state == VehicleProbeValueState.RAW_ONLY

        fun VehicleProbeReading.invalidated(state: VehicleProbeValueState, errorCode: String) = copy(
            state = state,
            numericValue = null,
            textValue = null,
            unit = null,
            errorCode = errorCode,
        )

        fun ByteArray.toHex(): String = buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }
}
