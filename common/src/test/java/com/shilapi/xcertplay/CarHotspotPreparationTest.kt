package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CarHotspotPreparationTest {
    @Test fun nonHotspotModeDoesNotStart() {
        val calls = AtomicInteger()
        val preparation = CarHotspotPreparation({ false }, { calls.incrementAndGet(); Result.READY }, { it() }, {})
        preparation.request(true)
        assertEquals(0, calls.get())
    }

    @Test fun repeatedRequestsShareTheRunningAttempt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pending = LinkedBlockingQueue<() -> Unit>()
        val results = mutableListOf<Result>()
        val calls = AtomicInteger()
        val preparation = CarHotspotPreparation({ it }, {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            Result.READY
        }, { pending.add(it) }, results::add)
        try {
            preparation.request(true)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            preparation.request(true)
        } finally {
            release.countDown()
        }
        pending.poll(3, TimeUnit.SECONDS)!!.invoke()
        assertEquals(1, calls.get())
        assertEquals(listOf(Result.READY), results)
    }

    @Test fun switchingAwayCancelsTheOldAttemptAndSwitchingBackStartsANewOne() {
        val cancellations = LinkedBlockingQueue<() -> Boolean>()
        val release = CountDownLatch(1)
        val pending = LinkedBlockingQueue<() -> Unit>()
        val results = mutableListOf<Result>()
        val preparation = CarHotspotPreparation({ it }, { cancelled ->
            cancellations.add(cancelled)
            check(release.await(3, TimeUnit.SECONDS))
            Result.READY
        }, { pending.add(it) }, results::add)
        try {
            preparation.request(true)
            val oldCancelled = cancellations.poll(3, TimeUnit.SECONDS)!!
            assertFalse(oldCancelled())
            preparation.request(false)
            assertTrue(oldCancelled())
            preparation.request(true)
            val newCancelled = cancellations.poll(3, TimeUnit.SECONDS)!!
            assertTrue(oldCancelled())
            assertFalse(newCancelled())
        } finally {
            release.countDown()
        }
        repeat(2) { pending.poll(3, TimeUnit.SECONDS)!!.invoke() }
        assertEquals(listOf(Result.READY), results)
    }

    @Test fun cancellationAfterWorkerCompletionDiscardsTheQueuedResult() {
        val pending = LinkedBlockingQueue<() -> Unit>()
        val results = mutableListOf<Result>()
        val preparation = CarHotspotPreparation({ true }, { Result.READY }, { pending.add(it) }, results::add)
        preparation.request(true)
        val deliver = pending.poll(3, TimeUnit.SECONDS)!!
        preparation.cancel()
        deliver()
        assertTrue(results.isEmpty())
    }

    @Test fun losingEligibilityCancelsTheWorkerEvenWithoutAnotherRequest() {
        var eligible = true
        val cancellations = LinkedBlockingQueue<() -> Boolean>()
        val release = CountDownLatch(1)
        val pending = LinkedBlockingQueue<() -> Unit>()
        val results = mutableListOf<Result>()
        val preparation = CarHotspotPreparation({ eligible }, { cancelled ->
            cancellations.add(cancelled)
            check(release.await(3, TimeUnit.SECONDS))
            Result.CANCELLED
        }, { pending.add(it) }, results::add)
        try {
            preparation.request(true)
            val cancelled = cancellations.poll(3, TimeUnit.SECONDS)!!
            eligible = false
            assertTrue(cancelled())
        } finally {
            release.countDown()
        }
        pending.poll(3, TimeUnit.SECONDS)!!.invoke()
        assertTrue(results.isEmpty())
    }

    @Test fun aFailureCanBeRetriedOnTheNextEvent() {
        val pending = LinkedBlockingQueue<() -> Unit>()
        val results = mutableListOf<Result>()
        val preparation = CarHotspotPreparation({ true }, { Result.PERMISSION_REQUIRED }, { pending.add(it) }, results::add)
        repeat(2) {
            preparation.request(true)
            pending.poll(3, TimeUnit.SECONDS)!!.invoke()
        }
        assertEquals(listOf(Result.PERMISSION_REQUIRED, Result.PERMISSION_REQUIRED), results)
    }
}
