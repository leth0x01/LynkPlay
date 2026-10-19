package com.shilapi.xcertplay.vehicle.probe

/** 固定只读白名单；候选信号不赋予未经验证的单位。 */
enum class VehicleProbeSignal(
    val key: String,
    val propertyId: Int,
    val byteCount: Int,
    val unit: String?,
) {
    ODOMETER("odometer", 0x287000a3, 4, "km"),
    RANGE("range", 0x287000a4, 2, "km"),
    SPEED("speed", 0x287000a8, 2, "km/h"),
    GEAR("gear", 0x287000ec, 1, null),
    RPM_RAW("rpm_raw", 0x2870023f, 2, null),
    FUEL_RAW("fuel_raw", 0x28700207, 1, null),
}

enum class VehicleProbeSource { POLL, CALLBACK }

enum class VehicleProbeValueState {
    WAITING, AVAILABLE, RAW_ONLY, UNSUPPORTED, UNAVAILABLE, ERROR,
    STALE, DISCONNECTED, STOPPED,
}

data class VehicleProbeObservation(
    val propertyId: Int,
    val areaId: Int,
    val status: Int,
    val sourceTimestampNanos: Long,
    val receivedElapsedNanos: Long,
    val rawBytes: ByteArray?,
    val source: VehicleProbeSource,
    val errorCode: String? = null,
)

data class VehicleProbeReading(
    val signal: VehicleProbeSignal,
    val state: VehicleProbeValueState,
    val rawHex: String?,
    val rawUnsigned: Long?,
    val numericValue: Double?,
    val textValue: String?,
    val unit: String?,
    val sourceStatus: Int?,
    val sourceTimestampNanos: Long?,
    val observedElapsedNanos: Long?,
    val ageMillis: Long?,
    val readCount: Long,
    val callbackCount: Long,
    val errorCode: String?,
)

data class VehicleProbeEvent(
    val reading: VehicleProbeReading,
    val source: VehicleProbeSource,
    val receivedElapsedNanos: Long,
)

data class VehicleProbeSnapshot(
    val connected: Boolean,
    val stopped: Boolean,
    val readings: List<VehicleProbeReading>,
    val history: List<VehicleProbeEvent>,
    val droppedEvents: Long,
)
