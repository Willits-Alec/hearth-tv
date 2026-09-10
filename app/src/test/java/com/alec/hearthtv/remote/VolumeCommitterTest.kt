package com.alec.hearthtv.remote

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9.2: a drag must not become a hundred network calls, and must not lose the value you let go on. */
class VolumeCommitterTest {
    @Test fun `a burst collapses to the newest value, never a stale one`() = runTest {
        val sent = mutableListOf<Int>()
        val c = VolumeCommitter(this, minIntervalMs = 100) { sent += it }
        (20..30).forEach { c.submit(it) }      // a whole drag inside one frame
        advanceTimeBy(10)
        assertEquals(listOf(30), sent)         // where the finger ended up, not where it started
        advanceUntilIdle()
        assertEquals(listOf(30), sent)         // and nothing superseded is sent afterwards
    }

    @Test fun `the value the finger stopped on always arrives`() = runTest {
        val sent = mutableListOf<Int>()
        val c = VolumeCommitter(this, minIntervalMs = 100) { sent += it }
        c.submit(5)
        advanceTimeBy(150)
        c.submit(6); c.submit(7); c.submit(8)
        advanceUntilIdle()
        assertEquals(8, sent.last())
    }

    @Test fun `sends are spaced by the interval, so a drag costs far fewer calls than it makes`() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val c = VolumeCommitter(this, minIntervalMs = 100) { sent += currentTime to it }
        repeat(6) { i ->
            c.submit(i)
            advanceTimeBy(30)
        }
        advanceUntilIdle()
        assertEquals(listOf(0L, 100L, 200L), sent.map { it.first })
        assertEquals(5, sent.last().second)
        sent.map { it.first }.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a} shorter than the interval", b - a >= 100)
        }
    }

    @Test fun `a later drag starts a fresh run once the first has drained`() = runTest {
        val sent = mutableListOf<Int>()
        val c = VolumeCommitter(this, minIntervalMs = 100) { sent += it }
        c.submit(1)
        advanceUntilIdle()
        c.submit(2)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), sent)
    }

    @Test fun `a slow send does not overlap itself`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val c = VolumeCommitter(this, minIntervalMs = 10) {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(200)
            inFlight--
        }
        repeat(10) { c.submit(it) }
        advanceUntilIdle()
        assertEquals(1, maxInFlight)
    }
}
