package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.VehicleIntegration
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeObservation
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSignal
import com.shilapi.xcertplay.vehicle.probe.VehicleProbeSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal interface Lynk06VehicleProbeBackend {
    fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit)
    fun supportedPropertyIds(): Set<Int>
    fun read(propertyId: Int): VehicleProbeObservation
    fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean
    fun close()
}

/** 所有入口由上层串行 I/O 槽调用；平台回调只通知，不获取 manager。 */
internal class ReflectiveLynk06VehicleProbeBackend internal constructor(
    private val context: Context,
    private val classLookup: (String) -> Class<*> = { Class.forName(it) },
    private val vehicleGate: () -> Boolean = { VehicleIntegration.isDetectedLynk06() },
    private val permissionGate: () -> Boolean = {
        // 与 Manifest、Service 共用同一常量，避免权限名分叉导致两层门禁不一致。
        context.checkSelfPermission(Lynk06VehicleProbeService.CAR_VENDOR_EXTENSION) == PackageManager.PERMISSION_GRANTED
    },
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : Lynk06VehicleProbeBackend {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var api: Api? = null
    private var car: Any? = null
    private var manager: Any? = null
    private var supported: Set<Int>? = null
    private val attemptedListeners = mutableListOf<Any>()

    override fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        checkOpen()
        checkGate()
        check(started.compareAndSet(false, true)) { "already_started" }
        try {
            val methods = Api(classLookup).also { api = it }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (!closed.get() && connected.compareAndSet(false, true)) onConnected()
                }

                override fun onServiceDisconnected(name: ComponentName?) = disconnected()
                override fun onBindingDied(name: ComponentName?) = disconnected()
                override fun onNullBinding(name: ComponentName?) = disconnected()

                private fun disconnected() {
                    connected.set(false)
                    if (!closed.get()) onDisconnected()
                }
            }
            val instance = methods.createCar.invoke(null, context, connection)
            check(methods.carClass.isInstance(instance)) { "invalid_car" }
            car = instance
            methods.connect.invoke(instance)
        } catch (error: Throwable) {
            throw sanitized(error)
        }
    }

    override fun supportedPropertyIds(): Set<Int> {
        checkReady()
        try {
            supported?.let { return it.toSet() }
            val methods = requireNotNull(api)
            val list = methods.propertyList.invoke(propertyManager()) as? List<*>
                ?: error("invalid_config_list")
            val result = linkedSetOf<Int>()
            for (config in list) {
                if (!methods.configClass.isInstance(config)) continue
                val id = methods.configId.invoke(config) as? Int ?: continue
                if (id !in signalsById) continue
                val access = methods.configAccess.invoke(config) as? Int ?: continue
                if (access != 1 && access != 3) continue
                val areas = methods.configAreas.invoke(config) as? IntArray ?: continue
                // 本车六个厂商属性没有 areaConfigs，OEM 生成空 getAreaIds()；
                // 空列表等同无分区，固定以 area 0 访问。显式非零区域仍拒绝。
                if (areas.isEmpty() || 0 in areas) result.add(id)
            }
            supported = result.toSet()
            return result
        } catch (error: Throwable) {
            throw sanitized(error)
        }
    }

    override fun read(propertyId: Int): VehicleProbeObservation {
        requireAllowed(propertyId)
        return try {
            checkReady()
            check(propertyId in supportedPropertyIds()) { "unsupported" }
            val value = requireNotNull(api).read.invoke(propertyManager(), ByteArray::class.java, propertyId, 0)
            observation(value, propertyId, VehicleProbeSource.POLL, clock())
        } catch (error: Throwable) {
            failedObservation(propertyId, VehicleProbeSource.POLL, clock(), errorCode(error))
        }
    }

    override fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean {
        requireAllowed(propertyId)
        checkReady()
        return try {
            check(propertyId in supportedPropertyIds()) { "unsupported" }
            val methods = requireNotNull(api)
            val target = propertyManager()
            val listener = Proxy.newProxyInstance(
                methods.listenerClass.classLoader,
                arrayOf(methods.listenerClass),
            ) { proxy, method, args ->
                when {
                    method.name == "equals" && method.parameterCount == 1 -> proxy === args?.getOrNull(0)
                    method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                    method.name == "toString" && method.parameterCount == 0 -> "VehicleProbeListener"
                    !closed.get() && connected.get() -> {
                        when (method.name) {
                            "onChangeEvent" -> {
                                val value = args?.getOrNull(0)
                                // 回调不是读取请求：陌生 ID、错误 area 及其他订阅的值直接丢弃。
                                if (matchesCallback(value, propertyId)) {
                                    val received = clock()
                                    val event = try {
                                        observation(value, propertyId, VehicleProbeSource.CALLBACK, received)
                                    } catch (error: Throwable) {
                                        failedObservation(propertyId, VehicleProbeSource.CALLBACK, received, errorCode(error))
                                    }
                                    if (!closed.get() && connected.get()) callback(event)
                                }
                            }
                            "onErrorEvent" -> {
                                val id = args?.getOrNull(0) as? Int
                                val area = args?.getOrNull(1) as? Int
                                if (id == propertyId && area == 0 && !closed.get() && connected.get()) {
                                    callback(failedObservation(propertyId, VehicleProbeSource.CALLBACK, clock(), "callback_error"))
                                }
                            }
                        }
                        null
                    }
                    else -> null
                }
            }
            // OEM 可能先产生注册副作用再抛异常，必须在调用前保留代理。
            attemptedListeners.add(listener)
            val result = methods.register.invoke(target, listener, propertyId, 1f)
            result as? Boolean ?: error("invalid_registration_result")
        } catch (error: Throwable) {
            throw sanitized(error)
        }
    }

    override fun close() {
        // 必须先逻辑失效；注销或 disconnect 可能阻塞或抛异常。
        if (!closed.compareAndSet(false, true)) return
        connected.set(false)
        val methods = api
        val target = manager
        val listeners = attemptedListeners.toList()
        attemptedListeners.clear()
        var failure: Throwable? = null
        for (listener in listeners) {
            try {
                if (target != null) methods?.unregister?.invoke(target, listener)
            } catch (error: Throwable) {
                // 每项独立尽力清理，不能跳过其他 listener 或 disconnect。
                if (failure == null) failure = error
            }
        }
        try {
            car?.let { methods?.disconnect?.invoke(it) }
        } catch (error: Throwable) {
            if (failure == null) failure = error
        } finally {
            manager = null
            car = null
            supported = null
        }
        // 全部尝试完成后才报告首个短错误码；不会重新激活本代后端。
        failure?.let { throw sanitized(it) }
    }

    private fun propertyManager(): Any {
        manager?.let { return it }
        val methods = requireNotNull(api)
        val result = methods.manager.invoke(checkNotNull(car) { "not_connected" }, "property")
        check(methods.managerClass.isInstance(result)) { "invalid_manager" }
        return checkNotNull(result).also { manager = it }
    }

    private fun checkOpen() = check(!closed.get()) { "closed" }

    private fun checkGate() {
        check(vehicleGate()) { "vehicle_not_allowed" }
        check(permissionGate()) { "vendor_permission_denied" }
    }

    private fun checkReady() {
        checkOpen()
        checkGate()
        check(connected.get()) { "not_connected" }
    }

    private fun requireAllowed(propertyId: Int) = require(propertyId in signalsById) { "property_not_allowed" }

    private fun matchesCallback(value: Any?, propertyId: Int): Boolean = try {
        val methods = requireNotNull(api)
        methods.valueClass.isInstance(value) &&
            methods.valueId.invoke(value) == propertyId && methods.valueArea.invoke(value) == 0
    } catch (_: Throwable) {
        false
    }

    private fun observation(
        value: Any?, propertyId: Int, source: VehicleProbeSource, received: Long,
    ): VehicleProbeObservation {
        val methods = requireNotNull(api)
        check(methods.valueClass.isInstance(value)) { "invalid_value_object" }
        val id = methods.valueId.invoke(value) as? Int ?: error("invalid_property_id")
        check(id == propertyId) { "property_id_mismatch" }
        val area = methods.valueArea.invoke(value) as? Int ?: error("invalid_area")
        check(area == 0) { "area_mismatch" }
        val status = methods.valueStatus.invoke(value) as? Int ?: error("invalid_status")
        val timestamp = methods.valueTimestamp.invoke(value) as? Long ?: error("invalid_timestamp")
        val raw = methods.valueBytes.invoke(value)
        val bytes = raw as? ByteArray
        val code = when {
            status !in 0..2 -> "invalid_status"
            timestamp < 0 -> "negative_timestamp"
            timestamp > received && timestamp - received > 1_000_000_000L -> "future_timestamp"
            status != 0 -> null
            raw == null -> "missing_value"
            bytes == null -> "invalid_value_type"
            bytes.size != signalsById.getValue(propertyId).byteCount -> "invalid_length"
            bytes.all { it == 0xff.toByte() } -> "all_ff"
            else -> null
        }
        return VehicleProbeObservation(
            propertyId, area, status, timestamp, received,
            // 仅保留已知长度的字节副本，不暴露 OEM 可变对象或任意大数据。
            bytes?.takeIf { it.size == signalsById.getValue(propertyId).byteCount }?.copyOf(), source, code,
        )
    }

    // 失败事件没有源时间，0 按契约仅表示未知；非可用状态与错误码阻止任何解码。
    private fun failedObservation(id: Int, source: VehicleProbeSource, received: Long, code: String) =
        VehicleProbeObservation(id, 0, -1, 0L, received, null, source, code)

    private class BackendFailure(val code: String) : IllegalStateException(code)

    private fun sanitized(error: Throwable) = BackendFailure(errorCode(error))

    private fun errorCode(error: Throwable): String {
        var cause = error
        while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
        return when (cause) {
            is BackendFailure -> cause.code
            is SecurityException -> "permission_denied"
            is ClassNotFoundException, is NoClassDefFoundError -> "car_api_unavailable"
            is NoSuchMethodException -> "car_api_signature"
            is IllegalAccessException -> "reflection_access"
            is IllegalStateException -> cause.message?.takeIf { it in localErrorCodes } ?: "car_state_error"
            is IllegalArgumentException -> "car_argument_error"
            else -> "car_call_failed"
        }
    }

    /** 只绑定已离线确认的固定只读 API；此类型仅在两个门禁通过后构造。 */
    private class Api(lookup: (String) -> Class<*>) {
        val carClass = lookup("android.car.Car")
        val managerClass = lookup("android.car.hardware.property.CarPropertyManager")
        val configClass = lookup("android.car.hardware.CarPropertyConfig")
        val valueClass = lookup("android.car.hardware.CarPropertyValue")
        val listenerClass = lookup("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
        val createCar: Method = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
        val connect: Method = carClass.getMethod("connect")
        val disconnect: Method = carClass.getMethod("disconnect")
        val manager: Method = carClass.getMethod("getCarManager", String::class.java)
        val propertyList: Method = managerClass.getMethod("getPropertyList")
        val read: Method = managerClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val register: Method = managerClass.getMethod("registerListener", listenerClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val unregister: Method = managerClass.getMethod("unregisterListener", listenerClass)
        val configId: Method = configClass.getMethod("getPropertyId")
        val configAccess: Method = configClass.getMethod("getAccess")
        val configAreas: Method = configClass.getMethod("getAreaIds")
        val valueId: Method = valueClass.getMethod("getPropertyId")
        val valueArea: Method = valueClass.getMethod("getAreaId")
        val valueStatus: Method = valueClass.getMethod("getStatus")
        val valueTimestamp: Method = valueClass.getMethod("getTimestamp")
        val valueBytes: Method = valueClass.getMethod("getValue")
    }

    private companion object {
        val signalsById = VehicleProbeSignal.entries.associateBy { it.propertyId }
        val localErrorCodes = setOf(
            "closed", "not_connected", "unsupported", "vehicle_not_allowed", "vendor_permission_denied",
            "invalid_car", "invalid_manager", "invalid_config_list", "invalid_registration_result",
            "invalid_value_object", "invalid_property_id", "property_id_mismatch", "invalid_area", "area_mismatch",
            "invalid_status", "invalid_timestamp",
        )
    }
}
