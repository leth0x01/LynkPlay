package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeObservation
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSignal
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSource
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeStore
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeValueState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Lynk06VehicleProbeBackendTest {
    private val odometer = VehicleProbeSignal.ODOMETER.propertyId
    private val range = VehicleProbeSignal.RANGE.propertyId
    private val forbidden = 0x287000b4
    private lateinit var car: FakeCar
    private lateinit var context: Context
    private val lookups = mutableListOf<String>()
    private var now = 10_000_000_000L

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        car = FakeCar()
        FakeCar.next = car
        lookups.clear()
        now = 10_000_000_000L
    }

    private fun backend(
        vehicle: () -> Boolean = { true },
        permission: () -> Boolean = { true },
        valueClass: Class<*> = FakeValue::class.java,
        configClass: Class<*> = FakeConfig::class.java,
    ) = ReflectiveLynk06VehicleProbeBackend(
        context = context,
        classLookup = { name ->
            lookups.add(name)
            when (name) {
                "android.car.Car" -> FakeCar::class.java
                "android.car.hardware.property.CarPropertyManager" -> FakeManager::class.java
                "android.car.hardware.CarPropertyConfig" -> configClass
                "android.car.hardware.CarPropertyValue" -> valueClass
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener" -> FakeListener::class.java
                else -> throw AssertionError("非预期类查找")
            }
        },
        vehicleGate = vehicle,
        permissionGate = permission,
        clock = { now },
    )

    private fun connected(backend: ReflectiveLynk06VehicleProbeBackend = backend()): ReflectiveLynk06VehicleProbeBackend {
        backend.connect({}, {})
        return backend
    }

    private fun expectCode(code: String, action: () -> Unit) {
        try {
            action()
            fail("预期异常：$code")
        } catch (error: IllegalStateException) {
            assertEquals(code, error.message)
            assertNull(error.cause)
        }
    }

    @Test fun rejectsVehicleBeforePermissionOrAnyCarClassLookup() {
        var permissionChecks = 0
        val backend = backend(vehicle = { false }, permission = { permissionChecks++; true })
        expectCode("vehicle_not_allowed") { backend.connect({}, {}) }
        assertEquals(0, permissionChecks)
        assertTrue(lookups.isEmpty())
        assertEquals(0, car.connectCalls)
        backend.close()
        assertEquals(0, car.disconnectCalls)
    }

    @Test fun rejectsPermissionBeforeAnyCarClassLookup() {
        val backend = backend(permission = { false })
        expectCode("vendor_permission_denied") { backend.connect({}, {}) }
        assertTrue(lookups.isEmpty())
        assertEquals(0, car.connectCalls)
        assertEquals("vendor_permission_denied", backend.read(odometer).errorCode)
        assertTrue(lookups.isEmpty())
    }

    @Test fun defaultVehicleGateRejectsHostWithoutAnyCarLookup() {
        val backend = ReflectiveLynk06VehicleProbeBackend(
            context, classLookup = { lookups.add(it); FakeCar::class.java }, permissionGate = { true },
        )
        expectCode("vehicle_not_allowed") { backend.connect({}, {}) }
        assertTrue(lookups.isEmpty())
    }

    @Test fun defaultPermissionCheckRejectsWithoutGrant() {
        val backend = ReflectiveLynk06VehicleProbeBackend(
            context, classLookup = { lookups.add(it); FakeCar::class.java }, vehicleGate = { true },
        )
        expectCode("vendor_permission_denied") { backend.connect({}, {}) }
        assertTrue(lookups.isEmpty())
    }

    @Test fun usesFixedSignaturesAndDoesNotAcquireManagerInServiceConnection() {
        var notifications = 0
        val backend = backend()
        backend.connect({ notifications++ }, {})
        assertEquals(1, notifications)
        assertEquals(1, car.connectCalls)
        assertEquals(0, car.managerCalls)
        assertEquals(0, car.properties.listCalls)
        assertEquals(5, lookups.size)
        assertEquals(VehicleProbeSignal.entries.map { it.propertyId }.toSet(), backend.supportedPropertyIds())
        assertEquals(1, car.managerCalls)
        assertEquals(listOf("property"), car.managerNames)
        assertEquals(1, car.properties.listCalls)
        assertTrue(car.properties.reads.isEmpty())
        assertEquals(java.lang.Boolean.TYPE, FakeManager::class.java.getMethod(
            "registerListener", FakeListener::class.java, Integer.TYPE, java.lang.Float.TYPE,
        ).returnType)
        assertEquals(java.lang.Long.TYPE, FakeValue::class.java.getMethod("getTimestamp").returnType)
    }

    @Test fun enumeratesOnlyMetadataAndFiltersReadableAreaZeroWhitelist() {
        val unknown = FakeConfig(forbidden, access = 3, areas = intArrayOf(0), throwOnAccess = true)
        car.properties.configs = listOf(
            FakeConfig(odometer, 1, intArrayOf(0)),
            FakeConfig(range, 3, intArrayOf(0, 1)),
            FakeConfig(VehicleProbeSignal.SPEED.propertyId, 2, intArrayOf(0)),
            FakeConfig(VehicleProbeSignal.GEAR.propertyId, 0, intArrayOf(0)),
            FakeConfig(VehicleProbeSignal.RPM_RAW.propertyId, 1, intArrayOf(1)),
            // 本车厂商属性没有 areaConfigs，OEM 据此生成空数组；必须按 area 0 放行。
            FakeConfig(VehicleProbeSignal.FUEL_RAW.propertyId, 1, intArrayOf()),
            unknown, null, "陌生配置对象",
        )
        val backend = connected()
        assertEquals(setOf(odometer, range, VehicleProbeSignal.FUEL_RAW.propertyId), backend.supportedPropertyIds())
        assertEquals(0, unknown.accessCalls)
        assertTrue(car.properties.reads.isEmpty())
        assertTrue(car.properties.registrations.isEmpty())
        assertEquals("unsupported", backend.read(VehicleProbeSignal.SPEED.propertyId).errorCode)
        assertTrue(car.properties.reads.isEmpty())
        expectCode("unsupported") { backend.subscribe(VehicleProbeSignal.SPEED.propertyId) {} }
        assertTrue(car.properties.registrations.isEmpty())
    }

    @Test fun emptyAreaListIsAcceptedAsAreaZeroButNonZeroOnlyIsRejected() {
        // 六项厂商属性在本车都是空 areaConfigs，全部必须放行并固定以 area 0 读取。
        car.properties.configs = VehicleProbeSignal.values().map { FakeConfig(it.propertyId, 1, intArrayOf()) }
        val backend = connected()
        assertEquals(VehicleProbeSignal.values().map { it.propertyId }.toSet(), backend.supportedPropertyIds())
        for (signal in VehicleProbeSignal.values()) {
            car.properties.value = FakeValue(signal.propertyId, 0, 0, 1L, ByteArray(signal.byteCount))
            backend.read(signal.propertyId)
        }
        assertEquals(VehicleProbeSignal.values().size, car.properties.reads.size)
        assertTrue(car.properties.reads.all { it.third == 0 })

        // 显式只声明非零区域时仍拒绝，不能把有分区的属性误当 area 0。
        // read() 把错误放进 observation（上层统一处理），不在业务层抛异常。
        val readsBefore = car.properties.reads.size
        car.properties.configs = listOf(FakeConfig(odometer, 1, intArrayOf(1, 4)))
        car.properties.value = FakeValue(odometer, 0, 0, 1L, ByteArray(4))
        assertEquals("unsupported", connected().read(odometer).errorCode)
        assertEquals(readsBefore, car.properties.reads.size)
    }

    @Test fun malformedConfigurationTypesCannotEnableReads() {
        car.properties.configs = listOf(
            LooseConfig(odometer.toLong(), 1, intArrayOf(0)),
            LooseConfig(range, 1L, intArrayOf(0)),
            LooseConfig(VehicleProbeSignal.SPEED.propertyId, 1, arrayOf(0)),
            LooseConfig(VehicleProbeSignal.GEAR.propertyId, 5, intArrayOf(0)),
        )
        val backend = connected(backend(configClass = LooseConfig::class.java))
        assertTrue(backend.supportedPropertyIds().isEmpty())
        assertTrue(car.properties.reads.isEmpty())
    }

    @Test fun readsExactlySixByteArrayPropertiesAtAreaZeroAndCopiesBytes() {
        val backend = connected()
        for (signal in VehicleProbeSignal.entries) {
            val bytes = ByteArray(signal.byteCount) { 1 }
            car.properties.value = FakeValue(signal.propertyId, bytes = bytes)
            val result = backend.read(signal.propertyId)
            assertEquals(signal.propertyId, result.propertyId)
            assertEquals(0, result.areaId)
            assertEquals(0, result.status)
            assertEquals(7L, result.sourceTimestampNanos)
            assertEquals(now, result.receivedElapsedNanos)
            assertEquals(VehicleProbeSource.POLL, result.source)
            assertNull(result.errorCode)
            assertArrayEquals(bytes, result.rawBytes)
            bytes[0] = 42
            assertEquals(1.toByte(), result.rawBytes!![0])
        }
        assertEquals(VehicleProbeSignal.entries.map { it.propertyId }, car.properties.reads.map { it.second })
        assertTrue(car.properties.reads.all { it.first == ByteArray::class.java && it.third == 0 })
    }

    @Test fun rejectsForeignPropertyBeforeAnyLookupReadOrSubscribe() {
        val backend = backend()
        for (action in listOf<() -> Unit>({ backend.read(forbidden) }, { backend.subscribe(forbidden) {} })) {
            try {
                action()
                fail("非白名单属性不应放行")
            } catch (error: IllegalArgumentException) {
                assertEquals("property_not_allowed", error.message)
            }
        }
        assertTrue(lookups.isEmpty())
        assertEquals(0, car.managerCalls)
        assertTrue(car.properties.reads.isEmpty())
    }

    @Test fun nullWrongObjectIdOrAreaAreErrorsNotZeroReadings() {
        val backend = connected()
        val invalid = listOf(
            null to "invalid_value_object",
            "陌生值" to "invalid_value_object",
            FakeValue(forbidden) to "property_id_mismatch",
            FakeValue(range) to "property_id_mismatch",
            FakeValue(odometer, area = 1) to "area_mismatch",
        )
        for ((value, code) in invalid) {
            car.properties.value = value
            val result = backend.read(odometer)
            assertEquals(code, result.errorCode)
            assertEquals(odometer, result.propertyId)
            assertEquals(-1, result.status)
            assertEquals(0L, result.sourceTimestampNanos)
            assertNull(result.rawBytes)
        }
    }

    @Test fun validatesStatusTimestampAndPayloadWithoutInventingValues() {
        val backend = connected()
        val invalid = listOf(
            FakeValue(odometer, status = 9) to "invalid_status",
            FakeValue(odometer, timestamp = -1) to "negative_timestamp",
            FakeValue(odometer, timestamp = now + 1_000_000_001L) to "future_timestamp",
            FakeValue(odometer, bytes = null) to "missing_value",
            FakeValue(odometer, bytes = arrayOf<Byte>(1, 2, 3, 4)) to "invalid_value_type",
            FakeValue(odometer, bytes = 0) to "invalid_value_type",
            FakeValue(odometer, bytes = byteArrayOf(1)) to "invalid_length",
            FakeValue(odometer, bytes = ByteArray(4) { 0xff.toByte() }) to "all_ff",
        )
        for ((value, code) in invalid) {
            car.properties.value = value
            assertEquals(code, backend.read(odometer).errorCode)
        }
        for (status in 1..2) {
            car.properties.value = FakeValue(odometer, status = status, timestamp = 0, bytes = null)
            val result = backend.read(odometer)
            assertEquals(status, result.status)
            assertEquals(0L, result.sourceTimestampNanos)
            assertNull(result.errorCode)
            val store = VehicleProbeStore().also { it.markConnected(); it.accept(result) }
            assertEquals(
                if (status == 1) VehicleProbeValueState.UNAVAILABLE else VehicleProbeValueState.ERROR,
                store.snapshot(now).readings.first { it.signal.propertyId == odometer }.state,
            )
        }
        car.properties.value = FakeValue(odometer, timestamp = now + 1_000_000_000L)
        assertNull(backend.read(odometer).errorCode)
    }

    @Test fun getterRuntimeTypesAreStrictNotNumberCoercions() {
        val backend = connected(backend(valueClass = LooseValue::class.java))
        val invalid = listOf(
            LooseValue(odometer.toLong(), 0, 0, 0L) to "invalid_property_id",
            LooseValue(odometer, 0L, 0, 0L) to "invalid_area",
            LooseValue(odometer, 0, 0L, 0L) to "invalid_status",
            LooseValue(odometer, 0, 0, 0) to "invalid_timestamp",
            LooseValue(odometer, 0, null, 0L) to "invalid_status",
        )
        for ((value, code) in invalid) {
            car.properties.value = value
            assertEquals(code, backend.read(odometer).errorCode)
        }
    }

    @Test fun subscribesAtRequestedRateOneButOnlyCountsActualNotifications() {
        val backend = connected()
        val observations = mutableListOf<VehicleProbeObservation>()
        for (signal in VehicleProbeSignal.entries) {
            assertTrue(backend.subscribe(signal.propertyId, observations::add))
        }
        assertTrue(observations.isEmpty())
        assertEquals(6, car.properties.registrations.size)
        assertTrue(car.properties.registrations.all { it.rate == 1f })
        val bytes = byteArrayOf(1, 2, 3, 4)
        car.properties.registrations.first().listener.onChangeEvent(FakeValue(odometer, bytes = bytes))
        bytes[0] = 42
        val result = observations.single()
        assertEquals(VehicleProbeSource.CALLBACK, result.source)
        assertEquals(now, result.receivedElapsedNanos)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.rawBytes)
    }

    @Test fun callbackFiltersUnknownIdOtherSubscriptionAndWrongArea() {
        val backend = connected()
        val observations = mutableListOf<VehicleProbeObservation>()
        backend.subscribe(odometer, observations::add)
        val listener = car.properties.registrations.single().listener
        listener.onChangeEvent(FakeValue(forbidden))
        listener.onChangeEvent(FakeValue(range))
        listener.onChangeEvent(FakeValue(odometer, area = 1))
        listener.onErrorEvent(forbidden, 0)
        listener.onErrorEvent(range, 0)
        listener.onErrorEvent(odometer, 1)
        val handler = Proxy.getInvocationHandler(listener)
        val change = FakeListener::class.java.getMethod("onChangeEvent", FakeValue::class.java)
        handler.invoke(listener, change, arrayOf("陌生对象"))
        handler.invoke(listener, change, arrayOf(null))
        val error = FakeListener::class.java.getMethod("onErrorEvent", Integer.TYPE, Integer.TYPE)
        handler.invoke(listener, error, arrayOf(odometer.toLong(), 0))
        assertTrue(observations.isEmpty())
        listener.onErrorEvent(odometer, 0)
        val result = observations.single()
        assertEquals("callback_error", result.errorCode)
        assertEquals(VehicleProbeSource.CALLBACK, result.source)
        assertEquals(odometer, result.propertyId)
        assertEquals(-1, result.status)
        assertNull(result.rawBytes)
    }

    @Test fun callbackValidationAndGetterExceptionBecomeBoundedErrors() {
        val backend = connected()
        val observations = mutableListOf<VehicleProbeObservation>()
        backend.subscribe(odometer, observations::add)
        val listener = car.properties.registrations.single().listener
        listener.onChangeEvent(FakeValue(odometer, bytes = "错误类型"))
        assertEquals("invalid_value_type", observations.last().errorCode)
        listener.onChangeEvent(object : FakeValue(odometer) {
            override fun getTimestamp(): Long = throw SecurityException("不应泄露的详细信息")
        })
        assertEquals("permission_denied", observations.last().errorCode)
    }

    @Test fun proxyObjectMethodsHaveIdentitySemanticsBeforeAndAfterClose() {
        val backend = connected()
        backend.subscribe(odometer) {}
        backend.subscribe(range) {}
        val first = car.properties.registrations[0].listener
        val second = car.properties.registrations[1].listener
        for (closed in listOf(false, true)) {
            if (closed) backend.close()
            assertTrue(first == first)
            assertFalse(first == second)
            assertFalse(first.equals(null))
            assertFalse(first.equals("VehicleProbeListener"))
            assertEquals(System.identityHashCode(first), first.hashCode())
            assertEquals("VehicleProbeListener", first.toString())
            assertEquals(2, hashSetOf(first, second).size)
        }
    }

    @Test fun tracksFalseAndPartiallyFailedRegistrationsAndCleansAllIndependently() {
        val backend = connected()
        car.properties.registerResult = false
        assertFalse(backend.subscribe(odometer) {})
        car.properties.registerFailure = SecurityException("私有消息")
        expectCode("permission_denied") { backend.subscribe(range) {} }
        car.properties.registerFailure = null
        assertFalse(backend.subscribe(VehicleProbeSignal.SPEED.propertyId) {})
        car.properties.unregisterFailure = IllegalStateException("注销失败")
        car.disconnectFailure = IllegalStateException("断开失败")
        expectCode("car_state_error") { backend.close() }
        assertEquals(car.properties.registrations.map { it.listener }, car.properties.unregisterAttempts)
        assertEquals(3, car.properties.unregisterAttempts.size)
        assertEquals(1, car.disconnectCalls)
        backend.close()
        assertEquals(3, car.properties.unregisterAttempts.size)
        assertEquals(1, car.disconnectCalls)
    }

    @Test fun closeInvalidatesCallbacksBeforePhysicalCleanupAndIgnoresLateServiceCallbacks() {
        var connectedCount = 0
        var disconnectedCount = 0
        val observations = mutableListOf<VehicleProbeObservation>()
        val backend = backend()
        backend.connect({ connectedCount++ }, { disconnectedCount++ })
        backend.subscribe(odometer, observations::add)
        val listener = car.properties.registrations.single().listener
        car.properties.onUnregister = {
            listener.onChangeEvent(FakeValue(odometer))
            listener.onErrorEvent(odometer, 0)
            car.connection.onServiceDisconnected(null)
            car.connection.onServiceConnected(null, null)
        }
        backend.close()
        listener.onChangeEvent(FakeValue(odometer))
        listener.onErrorEvent(odometer, 0)
        assertTrue(observations.isEmpty())
        assertEquals(1, connectedCount)
        assertEquals(0, disconnectedCount)
        assertEquals("closed", backend.read(odometer).errorCode)
        expectCode("closed") { backend.subscribe(odometer) {} }
        expectCode("closed") { backend.supportedPropertyIds() }
        assertEquals(1, car.properties.registrations.size)
    }

    @Test fun disconnectOnlyNotifiesAndSuppressesOldCallbacksWithoutManagerCalls() {
        var disconnectedCount = 0
        val observations = mutableListOf<VehicleProbeObservation>()
        val backend = backend()
        backend.connect({}, { disconnectedCount++ })
        backend.subscribe(odometer, observations::add)
        val listener = car.properties.registrations.single().listener
        val managerCalls = car.managerCalls
        val listCalls = car.properties.listCalls
        car.connection.onServiceDisconnected(null)
        listener.onChangeEvent(FakeValue(odometer))
        listener.onErrorEvent(odometer, 0)
        assertEquals(1, disconnectedCount)
        assertTrue(observations.isEmpty())
        assertEquals("not_connected", backend.read(odometer).errorCode)
        assertEquals(managerCalls, car.managerCalls)
        assertEquals(listCalls, car.properties.listCalls)
    }

    @Test fun gatesAreRecheckedBeforeFurtherManagerCalls() {
        var permitted = true
        val backend = connected(backend(permission = { permitted }))
        permitted = false
        assertEquals("vendor_permission_denied", backend.read(odometer).errorCode)
        expectCode("vendor_permission_denied") { backend.supportedPropertyIds() }
        expectCode("vendor_permission_denied") { backend.subscribe(odometer) {} }
        assertEquals(0, car.managerCalls)
        backend.close()
        assertEquals(1, car.disconnectCalls)
    }

    @Test fun unwrapsNestedInvocationExceptionsWithoutLeakingMessages() {
        val backend = connected()
        car.managerFailure = InvocationTargetException(SecurityException("含隐私的消息"))
        expectCode("permission_denied") { backend.supportedPropertyIds() }
        assertEquals("permission_denied", backend.read(odometer).errorCode)
        car.managerFailure = null
        car.properties.listFailure = SecurityException("列表隐私消息")
        assertEquals("permission_denied", backend.read(odometer).errorCode)
        car.properties.listFailure = null
        car.properties.readFailure = InvocationTargetException(InvocationTargetException(SecurityException("隐私")))
        assertEquals("permission_denied", backend.read(odometer).errorCode)
        car.properties.readFailure = IllegalArgumentException("隐私参数")
        assertEquals("car_argument_error", backend.read(odometer).errorCode)
        car.properties.readFailure = IllegalStateException("隐私服务状态")
        assertEquals("car_state_error", backend.read(odometer).errorCode)
    }

    @Test fun failedObservationsKeepErrorCodesAndCannotBecomeDecodedZeroInStore() {
        val backend = connected()
        val store = VehicleProbeStore().also { it.markConnected() }
        car.properties.readFailure = SecurityException("私有信息")
        assertTrue(store.accept(backend.read(odometer)))
        var result = store.snapshot(now).readings.first { it.signal.propertyId == odometer }
        assertEquals(VehicleProbeValueState.ERROR, result.state)
        assertEquals("permission_denied", result.errorCode)
        assertNull(result.numericValue)
        assertNull(result.rawUnsigned)
        assertNull(result.textValue)
        backend.subscribe(odometer) { store.accept(it) }
        car.properties.registrations.single().listener.onErrorEvent(odometer, 0)
        result = store.snapshot(now).readings.first { it.signal.propertyId == odometer }
        assertEquals("callback_error", result.errorCode)
        assertEquals(1L, result.readCount)
        assertEquals(1L, result.callbackCount)
        assertNull(result.numericValue)
    }

    @Test fun missingClassesAndWrongSignaturesFailBeforeConnecting() {
        val absent = ReflectiveLynk06VehicleProbeBackend(
            context, { throw ClassNotFoundException("私有路径") }, { true }, { true }, { now },
        )
        expectCode("car_api_unavailable") { absent.connect({}, {}) }
        val wrong = ReflectiveLynk06VehicleProbeBackend(
            context, { Any::class.java }, { true }, { true }, { now },
        )
        expectCode("car_api_signature") { wrong.connect({}, {}) }
        assertEquals(0, car.connectCalls)
        assertEquals(0, car.managerCalls)
    }

    @Test fun connectFailureStillAllowsDisconnectAndRejectsInvalidManager() {
        car.connectFailure = SecurityException("私有消息")
        val backend = backend()
        expectCode("permission_denied") { backend.connect({}, {}) }
        backend.close()
        assertEquals(1, car.disconnectCalls)
        setUp()
        car.managerResult = "错误 manager"
        val other = connected()
        assertEquals("invalid_manager", other.read(odometer).errorCode)
        assertTrue(car.properties.reads.isEmpty())
    }

    // 以下均为测试替身，不依赖 OEM 类或车辆 Binder。
    interface FakeListener {
        fun onChangeEvent(value: FakeValue)
        fun onErrorEvent(propertyId: Int, areaId: Int)
    }

    class FakeCar {
        val properties = FakeManager()
        lateinit var connection: ServiceConnection
        var connectCalls = 0
        var disconnectCalls = 0
        var managerCalls = 0
        val managerNames = mutableListOf<String>()
        var connectFailure: Throwable? = null
        var disconnectFailure: Throwable? = null
        var managerFailure: Throwable? = null
        var managerResult: Any? = properties

        fun connect() {
            connectCalls++
            connectFailure?.let { throw it }
            connection.onServiceConnected(ComponentName("test", "FakeCar"), null)
        }

        fun disconnect() {
            disconnectCalls++
            disconnectFailure?.let { throw it }
        }

        fun getCarManager(name: String): Any? {
            managerCalls++
            managerNames.add(name)
            managerFailure?.let { throw it }
            return managerResult
        }

        companion object {
            lateinit var next: FakeCar
            @JvmStatic fun createCar(context: Context, connection: ServiceConnection): FakeCar {
                assertNotNull(context)
                return next.also { it.connection = connection }
            }
        }
    }

    class FakeManager {
        data class Registration(val listener: FakeListener, val id: Int, val rate: Float)
        var configs: List<*> = VehicleProbeSignal.entries.map { FakeConfig(it.propertyId, 3, intArrayOf(0)) }
        var listCalls = 0
        var listFailure: Throwable? = null
        var value: Any? = null
        var readFailure: Throwable? = null
        var registerFailure: Throwable? = null
        var unregisterFailure: Throwable? = null
        var registerResult = true
        var onUnregister: (() -> Unit)? = null
        val reads = mutableListOf<Triple<Class<*>, Int, Int>>()
        val registrations = mutableListOf<Registration>()
        val unregisterAttempts = mutableListOf<FakeListener>()

        fun getPropertyList(): List<*> {
            listCalls++
            listFailure?.let { throw it }
            return configs
        }

        fun getProperty(type: Class<*>, id: Int, area: Int): Any? {
            reads.add(Triple(type, id, area))
            readFailure?.let { throw it }
            return value
        }

        fun registerListener(listener: FakeListener, id: Int, rate: Float): Boolean {
            registrations.add(Registration(listener, id, rate))
            registerFailure?.let { throw it }
            return registerResult
        }

        fun unregisterListener(listener: FakeListener) {
            unregisterAttempts.add(listener)
            onUnregister?.invoke()
            unregisterFailure?.let { throw it }
        }
    }

    class FakeConfig(
        private val id: Int, private val access: Int, private val areas: IntArray,
        private val throwOnAccess: Boolean = false,
    ) {
        var accessCalls = 0
        fun getPropertyId(): Int = id
        fun getAccess(): Int {
            accessCalls++
            check(!throwOnAccess) { "不应查询陌生属性的 access" }
            return access
        }
        fun getAreaIds(): IntArray = areas
    }

    class LooseConfig(private val id: Any?, private val access: Any?, private val areas: Any?) {
        fun getPropertyId(): Any? = id
        fun getAccess(): Any? = access
        fun getAreaIds(): Any? = areas
    }

    open class FakeValue(
        private val id: Int,
        private val area: Int = 0,
        private val status: Int = 0,
        private val timestamp: Long = 7L,
        private val bytes: Any? = byteArrayOf(1, 2, 3, 4),
    ) {
        fun getPropertyId(): Int = id
        fun getAreaId(): Int = area
        fun getStatus(): Int = status
        open fun getTimestamp(): Long = timestamp
        fun getValue(): Any? = bytes
    }

    class LooseValue(private val id: Any?, private val area: Any?, private val status: Any?, private val timestamp: Any?) {
        fun getPropertyId(): Any? = id
        fun getAreaId(): Any? = area
        fun getStatus(): Any? = status
        fun getTimestamp(): Any? = timestamp
        fun getValue(): Any? = byteArrayOf(1, 2, 3, 4)
    }
}
