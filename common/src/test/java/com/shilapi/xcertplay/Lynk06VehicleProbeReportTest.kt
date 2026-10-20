package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.probe.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Lynk06VehicleProbeReportTest {
    private fun report(id: String = UUID.randomUUID().toString()) = Lynk06VehicleProbeReport(
        id, "completed", "deadline", 123, 60_000, VehicleProbeStore().apply { stop() }.snapshot(0), false
    )

    @Test fun nullsAndCandidateFieldsAreExplicitAndNeverInventUnits() {
        val json = JSONObject(report().toJson())
        assertEquals(1, json.getInt("schemaVersion"))
        assertTrue(json.getBoolean("readOnly"))
        assertEquals("local_observation", json.getString("freshnessBasis"))
        val readings = json.getJSONArray("readings")
        assertEquals(6, readings.length())
        for (i in 0 until readings.length()) {
            val reading = readings.getJSONObject(i)
            assertTrue(reading.has("numericValue"))
            assertTrue(reading.isNull("numericValue"))
            if (reading.getString("key").endsWith("_raw")) {
                assertTrue(reading.isNull("textValue")); assertTrue(reading.isNull("unit"))
            }
        }
    }

    @Test fun subscriptionsSectionSeparatesRegisterFailureFromSilentCallbacks() {
        val id = "00000000-0000-0000-0000-0000000000bb"
        val report = report(id).copy(subscriptions = listOf(
            VehicleProbeSubscription(VehicleProbeSignal.ODOMETER.propertyId, true),
            VehicleProbeSubscription(VehicleProbeSignal.FUEL_RAW.propertyId, false),
        ))
        val json = JSONObject(report.toJson())
        val items = json.getJSONArray("subscriptions")
        assertEquals(2, items.length())
        assertEquals("0x287000a3", items.getJSONObject(0).getString("propertyId"))
        assertTrue(items.getJSONObject(0).getBoolean("registered"))
        assertEquals(0L, items.getJSONObject(0).getLong("callbacks"))
        assertEquals("0x28700207", items.getJSONObject(1).getString("propertyId"))
        assertFalse(items.getJSONObject(1).getBoolean("registered"))
    }

    @Test fun activeRunningReportSurvivesAFloodOfRejectedReports() {
        val root = Files.createTempDirectory("probe-active").toFile()
        try {
            val writer = AtomicVehicleProbeReportSink(root)
            val directory = File(root, "vehicle-probe").apply { mkdirs() }
            val activeId = "00000000-0000-0000-0000-0000000000aa"
            writer.write(report(activeId).copy(status = "running", reason = "starting"))
            repeat(6) { index ->
                writer.write(report("00000000-0000-0000-0000-${(0x100 + index).toString(16).padStart(12, '0')}")
                    .copy(status = "rejected", reason = "busy"))
            }
            val active = File(directory, "$activeId.json")
            assertTrue("活动会话的报告不能被拒绝报告淘汰", active.isFile)
            assertEquals("running", JSONObject(active.readText()).getString("status"))
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicReportsRetainOnlyFiveStrictUuidNamesAndIgnoreOtherFiles() {
        val root = Files.createTempDirectory("probe-report").toFile()
        try {
            val writer = AtomicVehicleProbeReportSink(root)
            val directory = File(root, "vehicle-probe").apply { mkdirs() }
            val untouched = File(directory, "not-a-request.json").apply { writeText("keep") }
            repeat(8) { index ->
                val report = report("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
                writer.write(report)
                assertEquals(report.requestId, JSONObject(File(directory, "${report.requestId}.json").readText()).getString("requestId"))
            }
            assertEquals(5, directory.listFiles()!!.count { VehicleProbeRequestIds.isCanonical(it.name.removeSuffix(".json")) })
            assertEquals("keep", untouched.readText())
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".new") || it.name.endsWith(".bak") })
        } finally { root.deleteRecursively() }
    }

    @Test fun sizeLimitDropsOldHistoryAndRecordsDroppingWithoutTruncatingJson() {
        val store = VehicleProbeStore()
        store.markConnected()
        repeat(256) { n -> store.accept(VehicleProbeObservation(VehicleProbeSignal.SPEED.propertyId, 0, 0, 0,
            n.toLong(), byteArrayOf(1, 0), VehicleProbeSource.POLL)) }
        val report = report().copy(snapshot = store.snapshot(256))
        val encoded = report.toJson(maxBytes = 10_000)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 10_000)
        val json = JSONObject(encoded)
        assertTrue(json.getLong("droppedEvents") > 0)
        assertTrue(json.getJSONArray("history").length() < 256)
    }

    @Test fun rawCandidatesRemainUndecodedEvenWhenObservedSuccessfully() {
        val store = VehicleProbeStore().apply { markConnected() }
        listOf(VehicleProbeSignal.RPM_RAW, VehicleProbeSignal.FUEL_RAW).forEach {
            store.accept(VehicleProbeObservation(it.propertyId, 0, 0, 0, 0,
                ByteArray(it.byteCount) { 1 }, VehicleProbeSource.CALLBACK))
        }
        val json = JSONObject(report().copy(snapshot = store.snapshot(0)).toJson())
        val readings = json.getJSONArray("readings")
        for (i in 4..5) {
            val raw = readings.getJSONObject(i)
            assertEquals("RAW_ONLY", raw.getString("state"))
            assertFalse(raw.isNull("rawUnsigned"))
            assertTrue(raw.isNull("numericValue")); assertTrue(raw.isNull("textValue")); assertTrue(raw.isNull("unit"))
        }
    }

    @Test fun reportDoesNotFollowUuidSymlinks() {
        val root = Files.createTempDirectory("probe-symlink").toFile()
        try {
            val directory = File(root, "vehicle-probe").apply { mkdirs() }
            val outside = File(root, "outside").apply { writeText("keep") }
            val report = report()
            try {
                Files.createSymbolicLink(File(directory, "${report.requestId}.json").toPath(), outside.toPath())
            } catch (failure: java.nio.file.FileSystemException) {
                // Windows hosts without Developer Mode/elevation cannot create the test fixture.
                // Linux CI still exercises the symlink rejection assertion below.
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    org.junit.Assume.assumeNoException("Host cannot create symbolic links", failure)
                }
                throw failure
            }
            assertThrows(Exception::class.java) { AtomicVehicleProbeReportSink(root).write(report) }
            assertEquals("keep", outside.readText())
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 8_000) fun watchdogWritesRealAtomicTerminalReportWhileVehicleIoIsBlocked() {
        val root = Files.createTempDirectory("probe-blocked-report").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val io = SingleVehicleProbeIo("probe-report-test")
        var now = 0L
        val actions = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler = object : VehicleProbeScheduler {
            override fun nowNanos() = now
            override fun wallTimeMillis() = 123L
            override fun schedule(delayMillis: Long, action: () -> Unit) { actions.add(delayMillis to action) }
        }
        val sink = AtomicVehicleProbeReportSink(root)
        val report = report()
        val session = Lynk06VehicleProbeSession(scheduler, io, {
            entered.countDown(); release.await(); error("create_failed")
        }, { null }, { VehicleProbeReportWriter.enqueue(sink, it) })
        try {
            session.start(report.requestId)
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            now = 3_000_000_000L
            actions.single { it.first == 3_000L }.second()
            VehicleProbeReportWriter.awaitIdleForTest()
            val json = JSONObject(File(root, "vehicle-probe/${report.requestId}.json").readText())
            assertEquals("failed", json.getString("status"))
            assertEquals("create_timeout", json.getString("reason"))
            assertTrue(json.getBoolean("cleanupPending"))
            assertTrue(io.busy)
        } finally {
            release.countDown()
            io.awaitIdleForTest(2_000)
            io.closeForTest()
            VehicleProbeReportWriter.awaitIdleForTest()
            root.deleteRecursively()
        }
    }

    @Test fun failedWriteDoesNotReturnSuccess() {
        val root = Files.createTempDirectory("probe-failure").toFile()
        try {
            File(root, "vehicle-probe").writeText("blocked")
            assertThrows(Exception::class.java) { AtomicVehicleProbeReportSink(root).write(report()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun manifestProtectsExportedServiceWithoutIntentFilter() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.byteInputStream())
        val android = "http://schemas.android.com/apk/res/android"
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == "com.shilapi.xcertplay.Lynk06VehicleProbeService" }
        assertEquals("true", service.getAttributeNS(android, "exported"))
        assertEquals("android.permission.DUMP", service.getAttributeNS(android, "permission"))
        assertEquals(0, service.getElementsByTagName("intent-filter").length)
        // 权限名必须与实车 dump、Service 和后端一致，否则两层门禁会分叉。
        assertTrue(manifest.contains("android.car.permission.CAR_VENDOR_EXTENSION"))
    }
}
