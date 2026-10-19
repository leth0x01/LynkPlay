package com.shilapi.xcertplay.vehicle.probe

import org.junit.Assert.*
import org.junit.Test

class VehicleProbeStoreTest {
    private val second = 1_000_000_000L

    private fun observation(
        signal: VehicleProbeSignal = VehicleProbeSignal.SPEED,
        bytes: ByteArray? = byteArrayOf(0x10, 0x27),
        time: Long = 10_000_000_000L,
        timestamp: Long = time,
        status: Int = 0,
        source: VehicleProbeSource = VehicleProbeSource.POLL,
        error: String? = null,
    ) = VehicleProbeObservation(signal.propertyId, 0, status, timestamp, time, bytes, source, error)

    private fun connectedStore(historyLimit: Int = 256, staleAfterNanos: Long = 3_000_000_000L) =
        VehicleProbeStore(historyLimit, staleAfterNanos).also { it.markConnected() }

    private fun VehicleProbeStore.reading(signal: VehicleProbeSignal = VehicleProbeSignal.SPEED, now: Long = 10_000_000_000L) =
        snapshot(now).readings.single { it.signal == signal }

    private fun assertNoValue(reading: VehicleProbeReading) {
        assertNull(reading.numericValue)
        assertNull(reading.textValue)
        assertNull(reading.unit)
    }

    @Test fun whitelistHasExactlySixFixedDefinitions() {
        assertEquals(
            listOf(
                listOf("odometer", 0x287000a3, 4, "km"),
                listOf("range", 0x287000a4, 2, "km"),
                listOf("speed", 0x287000a8, 2, "km/h"),
                listOf("gear", 0x287000ec, 1, null),
                listOf("rpm_raw", 0x2870023f, 2, null),
                listOf("fuel_raw", 0x28700207, 1, null),
            ),
            VehicleProbeSignal.values().map { listOf(it.key, it.propertyId, it.byteCount, it.unit) },
        )
        val snapshot = VehicleProbeStore().snapshot(0)
        assertEquals(6, snapshot.readings.size)
        assertTrue(snapshot.readings.all { it.state == VehicleProbeValueState.WAITING })
        assertFalse(snapshot.connected)
        assertFalse(snapshot.stopped)
    }

    @Test fun decodesVerifiedFieldsAndUnsignedLittleEndian() {
        val store = connectedStore()
        store.accept(observation(VehicleProbeSignal.ODOMETER, byteArrayOf(0x7c, 0x91.toByte(), 6, 0)))
        store.accept(observation(VehicleProbeSignal.RANGE, byteArrayOf(0xa1.toByte(), 2)))
        store.accept(observation())
        store.accept(observation(VehicleProbeSignal.GEAR, byteArrayOf(5)))
        assertEquals(43046.0, store.reading(VehicleProbeSignal.ODOMETER).numericValue!!, 0.0001)
        assertEquals(673.0, store.reading(VehicleProbeSignal.RANGE).numericValue!!, 0.0)
        assertEquals(100.0, store.reading().numericValue!!, 0.0)
        assertEquals("P", store.reading(VehicleProbeSignal.GEAR).textValue)
        assertNull(store.reading(VehicleProbeSignal.GEAR).numericValue)
        assertEquals("km/h", store.reading().unit)
        store.accept(observation(VehicleProbeSignal.ODOMETER, byteArrayOf(0xfe.toByte(), -1, -1, -1)))
        assertEquals(4_294_967_294L, store.reading(VehicleProbeSignal.ODOMETER).rawUnsigned)
        assertEquals(429_496_729.4, store.reading(VehicleProbeSignal.ODOMETER).numericValue!!, 0.001)
        assertEquals("FEFFFFFF", store.reading(VehicleProbeSignal.ODOMETER).rawHex)
    }

    @Test fun onlyFourKnownGearsAreDecodedAndUnknownRetainsRawDiagnostics() {
        val store = connectedStore()
        mapOf(2 to "D", 3 to "N", 4 to "R", 5 to "P").forEach { (raw, expected) ->
            store.accept(observation(VehicleProbeSignal.GEAR, byteArrayOf(raw.toByte())))
            assertEquals(expected, store.reading(VehicleProbeSignal.GEAR).textValue)
        }
        for (raw in listOf(0, 1, 6, 254)) {
            store.accept(observation(VehicleProbeSignal.GEAR, byteArrayOf(raw.toByte())))
            val reading = store.reading(VehicleProbeSignal.GEAR)
            assertEquals(VehicleProbeValueState.ERROR, reading.state)
            assertEquals(raw.toLong(), reading.rawUnsigned)
            assertEquals("unknown_gear", reading.errorCode)
            assertNoValue(reading)
        }
    }

    @Test fun candidateFieldsNeverClaimDecodedValuesOrUnits() {
        val store = connectedStore()
        for (signal in listOf(VehicleProbeSignal.RPM_RAW, VehicleProbeSignal.FUEL_RAW)) {
            store.accept(observation(signal, ByteArray(signal.byteCount) { 0x80.toByte() }))
            val reading = store.reading(signal)
            assertEquals(VehicleProbeValueState.RAW_ONLY, reading.state)
            assertNotNull(reading.rawUnsigned)
            assertNotNull(reading.rawHex)
            assertNoValue(reading)
        }
    }

    @Test fun invalidStatusMissingWrongLengthAllFfAndBackendErrorCannotDecode() {
        val cases = listOf(
            observation(status = 1) to VehicleProbeValueState.UNAVAILABLE,
            observation(status = 2) to VehicleProbeValueState.ERROR,
            observation(status = -1) to VehicleProbeValueState.ERROR,
            observation(bytes = null) to VehicleProbeValueState.ERROR,
            observation(bytes = byteArrayOf()) to VehicleProbeValueState.ERROR,
            observation(bytes = byteArrayOf(1)) to VehicleProbeValueState.ERROR,
            observation(bytes = byteArrayOf(1, 2, 3)) to VehicleProbeValueState.ERROR,
            observation(bytes = byteArrayOf(-1, -1)) to VehicleProbeValueState.ERROR,
            observation(error = "read_failed") to VehicleProbeValueState.ERROR,
        )
        for ((input, expected) in cases) {
            val store = connectedStore()
            store.accept(observation())
            assertTrue(store.accept(input))
            val reading = store.reading()
            assertEquals(expected, reading.state)
            assertEquals(input.status, reading.sourceStatus)
            assertNoValue(reading)
            assertNotNull(reading.errorCode)
        }
    }

    @Test fun zeroIsValidButAllFfIsRejectedForEverySignal() {
        for (signal in VehicleProbeSignal.values()) {
            val store = connectedStore()
            store.accept(observation(signal, ByteArray(signal.byteCount)))
            if (signal != VehicleProbeSignal.GEAR) {
                assertTrue(store.reading(signal).state in setOf(VehicleProbeValueState.AVAILABLE, VehicleProbeValueState.RAW_ONLY))
                assertEquals(0L, store.reading(signal).rawUnsigned)
            }
            store.accept(observation(signal, ByteArray(signal.byteCount) { -1 }))
            assertEquals("all_ff", store.reading(signal).errorCode)
            assertNoValue(store.reading(signal))
        }
    }

    @Test fun onlyConnectedWhitelistAreaZeroObservationsAreAccepted() {
        val store = VehicleProbeStore()
        assertFalse(store.accept(observation()))
        store.markConnected()
        assertFalse(store.accept(observation().copy(propertyId = 0x287000b4)))
        assertFalse(store.accept(observation().copy(areaId = 1)))
        assertTrue(store.snapshot(10 * second).history.isEmpty())
        assertEquals(0L, store.reading().readCount)
        assertTrue(store.accept(observation()))
        store.markDisconnected()
        assertFalse(store.accept(observation()))
        assertEquals(1L, store.reading().readCount)
    }

    @Test fun freshnessUsesSuccessfulLocalObservationNotSourceChangesOrSnapshots() {
        val store = connectedStore()
        store.accept(observation(timestamp = 0))
        assertEquals(0L, store.reading().sourceTimestampNanos)
        assertEquals(0L, store.reading().ageMillis)
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = 12 * second).state)
        assertEquals(2000L, store.reading(now = 12 * second).ageMillis)
        assertEquals(VehicleProbeValueState.STALE, store.reading(now = 13 * second).state)
        assertNoValue(store.reading(now = 13 * second))
        assertEquals(1L, store.reading(now = 13 * second).readCount)
        assertEquals(1, store.snapshot(13 * second).history.size)
        store.accept(observation(time = 14 * second, timestamp = 0))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = 14 * second).state)
        assertEquals(0L, store.reading(now = 14 * second).ageMillis)
        // 源缓存时间不变，每秒成功主动读取仍是新的本地观察。
        store.accept(observation(time = 15 * second, timestamp = 0))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = 17 * second).state)
        assertEquals(3L, store.reading(now = 17 * second).readCount)
    }

    @Test fun repeatedPositiveTimestampAlsoRefreshesLocalFreshness() {
        val store = connectedStore()
        store.accept(observation())
        store.accept(observation(time = 12 * second, timestamp = 10 * second))
        assertEquals(0L, store.reading(now = 12 * second).ageMillis)
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = 14 * second).state)
        assertEquals(VehicleProbeValueState.STALE, store.reading(now = 15 * second).state)
    }

    @Test fun oldPositiveTimestampCannotOverwriteOrRefreshAndZeroCannotEraseBaseline() {
        val store = connectedStore()
        store.accept(observation())
        store.accept(observation(time = 11 * second, timestamp = 0))
        assertFalse(store.accept(observation(bytes = byteArrayOf(1, 0), time = 12 * second, timestamp = 9 * second)))
        val reading = store.reading(now = 12 * second)
        assertEquals(100.0, reading.numericValue!!, 0.0)
        assertEquals(11 * second, reading.observedElapsedNanos)
        assertEquals(3L, reading.readCount)
        assertEquals("out_of_order", store.snapshot(12 * second).history.last().reading.errorCode)
        assertEquals(VehicleProbeValueState.STALE, store.reading(now = 14 * second).state)
    }

    @Test fun negativeFutureAndRegressingSnapshotTimesCannotBeValid() {
        for (input in listOf(
            observation(timestamp = -1),
            observation(timestamp = 11 * second + 1),
            observation(time = -1, timestamp = 0),
        )) {
            val store = connectedStore()
            store.accept(input)
            assertEquals(VehicleProbeValueState.ERROR, store.reading().state)
            assertNoValue(store.reading())
        }
        val store = connectedStore()
        store.accept(observation(timestamp = 11 * second))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading().state)
        assertFalse(store.accept(observation(time = 9 * second, timestamp = 0)))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading().state)
        assertEquals("out_of_order_local", store.snapshot(10 * second).history.last().reading.errorCode)
        val other = connectedStore()
        other.accept(observation())
        assertEquals(VehicleProbeValueState.ERROR, other.reading(now = 9 * second).state)
        assertNoValue(other.reading(now = 9 * second))
    }

    @Test fun failedObservationsDoNotPoisonSourceBaselineAndKeepDiagnosticMetadata() {
        val store = connectedStore()
        store.accept(observation(timestamp = 0))
        store.accept(observation(time = 11 * second, timestamp = 50 * second,
            source = VehicleProbeSource.CALLBACK))
        val failed = store.reading(now = 11 * second)
        assertEquals("future_timestamp", failed.errorCode)
        assertEquals(50 * second, failed.sourceTimestampNanos)
        assertEquals(11 * second, failed.observedElapsedNanos)
        assertEquals(0, failed.sourceStatus)
        assertEquals(1L, failed.readCount)
        assertEquals(1L, failed.callbackCount)
        assertTrue(store.accept(observation(time = 12 * second, timestamp = 12 * second)))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = 12 * second).state)
        val history = store.snapshot(12 * second).history
        assertEquals(0L, history.first().reading.sourceTimestampNanos)
        assertEquals(0L, history.first().reading.callbackCount)
        assertEquals(VehicleProbeValueState.ERROR, history[1].reading.state)
        assertEquals(VehicleProbeSource.CALLBACK, history[1].source)
        assertNoValue(history[1].reading)
    }

    @Test fun candidateFieldsBecomeStaleAndRecoverOnlyOnSuccessfulObservation() {
        val store = connectedStore()
        store.accept(observation(VehicleProbeSignal.RPM_RAW, byteArrayOf(1, 2)))
        val stale = store.reading(VehicleProbeSignal.RPM_RAW, now = 13 * second)
        assertEquals(VehicleProbeValueState.STALE, stale.state)
        assertEquals(3000L, stale.ageMillis)
        assertNoValue(stale)
        store.accept(observation(VehicleProbeSignal.RPM_RAW, byteArrayOf(1, 2), time = 14 * second))
        assertEquals(VehicleProbeValueState.RAW_ONLY, store.reading(VehicleProbeSignal.RPM_RAW, now = 14 * second).state)
    }

    @Test fun futureComparisonDoesNotOverflowAtLongMaximum() {
        val store = connectedStore()
        store.accept(observation(time = Long.MAX_VALUE - 1, timestamp = Long.MAX_VALUE))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = Long.MAX_VALUE).state)
    }

    @Test fun disconnectErrorUnsupportedAndStopInvalidateCurrentButNotHistory() {
        val store = connectedStore()
        store.accept(observation())
        store.markError(VehicleProbeSignal.SPEED, "read_timeout")
        assertEquals(VehicleProbeValueState.ERROR, store.reading().state)
        assertEquals("read_timeout", store.reading().errorCode)
        assertNoValue(store.reading())
        store.markUnsupported(VehicleProbeSignal.RANGE)
        assertEquals(VehicleProbeValueState.UNSUPPORTED, store.reading(VehicleProbeSignal.RANGE).state)
        store.markDisconnected()
        assertTrue(store.snapshot(10 * second).readings.all { it.state == VehicleProbeValueState.DISCONNECTED })
        store.stop("deadline")
        val stopped = store.snapshot(10 * second)
        assertFalse(stopped.connected)
        assertTrue(stopped.stopped)
        assertTrue(stopped.readings.all { it.state == VehicleProbeValueState.STOPPED && it.errorCode == "deadline" })
        stopped.readings.forEach(::assertNoValue)
        assertEquals(VehicleProbeValueState.AVAILABLE, stopped.history.single().reading.state)
        assertEquals(100.0, stopped.history.single().reading.numericValue!!, 0.0)
    }

    @Test fun reconnectClearsValuesAndTimeBaselinesButPreservesCountsAndHistory() {
        val store = connectedStore()
        store.accept(observation())
        store.accept(observation(source = VehicleProbeSource.CALLBACK))
        store.markDisconnected()
        store.markConnected()
        val waiting = store.reading(now = second)
        assertEquals(VehicleProbeValueState.WAITING, waiting.state)
        assertNull(waiting.rawHex)
        assertNull(waiting.rawUnsigned)
        assertNull(waiting.sourceTimestampNanos)
        assertNull(waiting.observedElapsedNanos)
        assertNull(waiting.ageMillis)
        assertNull(waiting.sourceStatus)
        assertEquals(1L, waiting.readCount)
        assertEquals(1L, waiting.callbackCount)
        assertTrue(store.accept(observation(time = second, timestamp = second)))
        assertEquals(VehicleProbeValueState.AVAILABLE, store.reading(now = second).state)
        assertEquals(3, store.snapshot(second).history.size)
    }

    @Test fun stopIsTerminalAndIdempotent() {
        val store = connectedStore()
        store.accept(observation())
        store.stop("first_stop")
        store.markConnected()
        store.markDisconnected()
        store.markError(VehicleProbeSignal.SPEED, "late_error")
        store.markUnsupported(VehicleProbeSignal.SPEED)
        store.stop("late_stop")
        assertFalse(store.accept(observation(time = 11 * second)))
        val snapshot = store.snapshot(11 * second)
        assertTrue(snapshot.stopped)
        assertFalse(snapshot.connected)
        assertEquals("first_stop", store.reading(now = 11 * second).errorCode)
        assertEquals(1L, store.reading(now = 11 * second).readCount)
        assertEquals(1, snapshot.history.size)
    }

    @Test fun inputBytesAndReturnedListsCannotChangeStoredHistory() {
        val bytes = byteArrayOf(0x10, 0x27)
        val store = connectedStore()
        store.accept(observation(bytes = bytes))
        val first = store.snapshot(10 * second)
        bytes.fill(0)
        assertEquals("1027", store.reading().rawHex)
        assertEquals("1027", first.history.single().reading.rawHex)
        // 即使调用方强转并尝试更改列表，也不能影响内部容器。
        runCatching { (first.history as MutableList<VehicleProbeEvent>).clear() }
        runCatching { (first.readings as MutableList<VehicleProbeReading>).clear() }
        assertEquals(1, store.snapshot(10 * second).history.size)
        assertEquals(6, store.snapshot(10 * second).readings.size)
    }

    @Test fun historyIsBoundedAndEvictionsAreExact() {
        val store = connectedStore(historyLimit = 999)
        repeat(300) { index ->
            store.accept(observation(time = 10 * second + index, timestamp = 0,
                source = if (index % 2 == 0) VehicleProbeSource.POLL else VehicleProbeSource.CALLBACK))
        }
        val snapshot = store.snapshot(11 * second)
        assertEquals(256, snapshot.history.size)
        assertEquals(44L, snapshot.droppedEvents)
        assertEquals(10 * second + 44, snapshot.history.first().receivedElapsedNanos)
        assertEquals(150L, store.reading(now = 11 * second).readCount)
        assertEquals(150L, store.reading(now = 11 * second).callbackCount)
        assertEquals(VehicleProbeSource.CALLBACK, snapshot.history.last().source)
        val small = connectedStore(historyLimit = 2)
        repeat(5) { small.accept(observation()) }
        assertEquals(2, small.snapshot(10 * second).history.size)
        assertEquals(3L, small.snapshot(10 * second).droppedEvents)
        val disabled = connectedStore(historyLimit = 0)
        disabled.accept(observation())
        assertEquals(1L, disabled.snapshot(10 * second).droppedEvents)
        assertTrue(disabled.snapshot(10 * second).history.isEmpty())
    }

    @Test fun deliveryOrderAcrossDifferentSignalsDoesNotLookLikeClockRegression() {
        val store = connectedStore()
        store.accept(observation(time = 2 * second, timestamp = 0))
        assertTrue(store.accept(observation(VehicleProbeSignal.ODOMETER,
            byteArrayOf(0x7c, 0x91.toByte(), 6, 0), time = 2 * second - 1, timestamp = 0)))
        val reading = store.reading(VehicleProbeSignal.ODOMETER, now = 2 * second)
        assertEquals(VehicleProbeValueState.AVAILABLE, reading.state)
        assertEquals(43046.0, reading.numericValue!!, 0.001)
    }

    @Test fun delayedLocalReceiptDoesNotReplaceOrInvalidateNewerValue() {
        val store = connectedStore()
        store.accept(observation(bytes = byteArrayOf(0x88.toByte(), 0x13), time = 2 * second, timestamp = 0))
        assertFalse(store.accept(observation(bytes = byteArrayOf(0xe8.toByte(), 3), time = second,
            timestamp = 0, source = VehicleProbeSource.CALLBACK)))
        val reading = store.reading(now = 2 * second)
        assertEquals(VehicleProbeValueState.AVAILABLE, reading.state)
        assertEquals(50.0, reading.numericValue!!, 0.0)
        assertEquals(1L, reading.callbackCount)
        assertEquals("out_of_order_local", store.snapshot(2 * second).history.last().reading.errorCode)
    }

    @Test fun malformedArraysCannotRetainUnboundedRawDataInReadingsOrHistory() {
        val store = connectedStore()
        store.accept(observation(bytes = ByteArray(4096), timestamp = 0))
        val snapshot = store.snapshot(10 * second)
        assertEquals("length_mismatch", snapshot.readings.single { it.signal == VehicleProbeSignal.SPEED }.errorCode)
        assertNull(snapshot.history.single().reading.rawHex)
        assertNull(snapshot.history.single().reading.rawUnsigned)
        assertNoValue(snapshot.history.single().reading)
    }

    @Test fun customFreshnessAndConcurrentObservationsKeepExactCounters() {
        val store = connectedStore(staleAfterNanos = second)
        val threads = List(4) {
            Thread { repeat(100) { store.accept(observation(timestamp = 0)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(400L, store.reading().readCount)
        assertEquals(144L, store.snapshot(10 * second).droppedEvents)
        assertEquals(VehicleProbeValueState.STALE, store.reading(now = 11 * second).state)
    }
}
