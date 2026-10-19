package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.vehicle.VehicleIntegration

/** 仅响应受 Manifest DUMP 权限保护的显式诊断请求，不参与 CarPlay 生命周期。 */
class Lynk06VehicleProbeService : Service() {
    private var session: Lynk06VehicleProbeSession? = null
    private var latestStartId = 0
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val requestId = intent?.getStringExtra(REQUEST_ID)
        val action = intent?.action
        if (!VehicleProbeRequestIds.isCanonical(requestId) || (action != ACTION_START && action != ACTION_STOP)) {
            if (session?.isRunningRequest() != true) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (action == ACTION_START) {
            // Service 重建也不能让旧 UUID 重新开始，或用 busy 报告覆盖旧请求终态。
            if (rememberRequest(requestId!!, session?.currentRequestId())) {
                if (session == null) session = createSession()
                session?.start(requestId)
            }
        } else {
            session?.stop(requestId!!)
        }
        if (session?.isRunningRequest() != true) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun createSession(): Lynk06VehicleProbeSession? {
        val root = getExternalFilesDir(null)
        if (root == null) {
            Log.e("VehicleProbe", "报告目录不可用")
            return null
        }
        val sink = AtomicVehicleProbeReportSink(root)
        return Lynk06VehicleProbeSession(
            SystemVehicleProbeScheduler,
            ProcessVehicleProbeIo,
            { ReflectiveLynk06VehicleProbeBackend(applicationContext) },
            {
                vehicleProbeGate(
                    VehicleIntegration.isDetectedLynk06(),
                    checkSelfPermission(CAR_VENDOR_EXTENSION) == PackageManager.PERMISSION_GRANTED
                )
            },
            { report -> VehicleProbeReportWriter.enqueue(sink, report) },
            { main.post { if (session?.isRunningRequest() != true) stopSelfResult(latestStartId) } }
        )
    }

    override fun onDestroy() {
        session?.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.leth0x01.lynkplay.action.VEHICLE_PROBE_START"
        const val ACTION_STOP = "com.leth0x01.lynkplay.action.VEHICLE_PROBE_STOP"
        const val REQUEST_ID = "request_id"
        internal const val CAR_VENDOR_EXTENSION = "android.car.permission.CAR_VENDOR_EXTENSION"
        private val startedRequests = LinkedHashSet<String>()
        private const val MAX_REMEMBERED_REQUESTS = 64

        /**
         * 跨 Service 重建保持本次 UUID 的终态不被 busy 报告覆盖，同时防止去重集无限增长。
         * 超限时优先移除最旧的非当前活动请求。
         */
        @Synchronized internal fun rememberRequest(requestId: String, activeId: String?): Boolean {
            if (!startedRequests.add(requestId)) return false
            if (startedRequests.size > MAX_REMEMBERED_REQUESTS) {
                startedRequests.firstOrNull { it != requestId && it != activeId }?.let(startedRequests::remove)
            }
            return true
        }
    }
}

internal fun vehicleProbeGate(detected: Boolean, permissionGranted: Boolean): String? = when {
    !detected -> "vehicle_gate_denied"
    !permissionGranted -> "permission_denied"
    else -> null
}
