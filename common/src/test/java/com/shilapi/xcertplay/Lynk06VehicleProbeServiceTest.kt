package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Lynk06VehicleProbeServiceTest {
    @Test fun unboundAndNonStickyForNullInvalidAndUnmatchedStopRequests() {
        val controller = Robolectric.buildService(Lynk06VehicleProbeService::class.java).create()
        val service = controller.get()
        try {
            assertNull(service.onBind(Intent()))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(Lynk06VehicleProbeService.ACTION_START)
                .putExtra("request_id", "../not-a-uuid"), 0, 2))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(Lynk06VehicleProbeService.ACTION_STOP)
                .putExtra("request_id", "12345678-1234-1234-1234-123456789abc"), 0, 3))
        } finally { controller.destroy() }
    }

    @Test fun duplicateStartAfterServiceRecreationDoesNotOverwriteItsReport() {
        val id = java.util.UUID.randomUUID().toString()
        val intent = Intent(Lynk06VehicleProbeService.ACTION_START).putExtra("request_id", id)
        val first = Robolectric.buildService(Lynk06VehicleProbeService::class.java).create()
        val file = java.io.File(first.get().getExternalFilesDir(null), "vehicle-probe/$id.json")
        try {
            assertEquals(Service.START_NOT_STICKY, first.get().onStartCommand(intent, 0, 1))
            VehicleProbeReportWriter.awaitIdleForTest()
            assertTrue(file.isFile)
            assertTrue(file.setLastModified(1_000))
        } finally { first.destroy() }
        val second = Robolectric.buildService(Lynk06VehicleProbeService::class.java).create()
        try {
            second.get().onStartCommand(intent, 0, 2)
            VehicleProbeReportWriter.awaitIdleForTest()
            assertEquals(1_000L, file.lastModified())
        } finally { second.destroy(); file.delete() }
    }

    @Test fun gateRequiresBothFullVehicleMatchAndPermission() {
        assertEquals("vehicle_gate_denied", vehicleProbeGate(false, true))
        assertEquals("permission_denied", vehicleProbeGate(true, false))
        assertNull(vehicleProbeGate(true, true))
    }

    @Test fun manifestServiceAndBackendUseTheSameVendorPermissionName() {
        // 实车包 dump 中的授予项就是 android.car.permission.CAR_VENDOR_EXTENSION。
        val expected = "android.car.permission.CAR_VENDOR_EXTENSION"
        assertEquals(expected, Lynk06VehicleProbeService.CAR_VENDOR_EXTENSION)
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"$expected\""))
        assertFalse(manifest.contains("<uses-permission android:name=\"android.permission.CAR_VENDOR_EXTENSION\""))
        val backend = java.io.File(
            "src/main/java/com/shilapi/xcertplay/Lynk06VehicleProbeBackend.kt",
        ).readText()
        assertTrue(backend.contains("Lynk06VehicleProbeService.CAR_VENDOR_EXTENSION"))
        assertFalse(backend.contains("\"android.car.permission.CAR_VENDOR_EXTENSION\""))
    }

    @Test fun idsMustBeCanonicalNotJustUuidParseable() {
        assertFalse(VehicleProbeRequestIds.isCanonical("1-1-1-1-1"))
        assertFalse(VehicleProbeRequestIds.isCanonical("ABCDEFAB-1234-1234-1234-123456789ABC"))
        assertFalse(VehicleProbeRequestIds.isCanonical(null))
        assertTrue(VehicleProbeRequestIds.isCanonical("abcdefab-1234-1234-1234-123456789abc"))
    }
}
