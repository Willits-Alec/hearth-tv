package com.alec.hearthtv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Diagnostics error list: newest first, bounded, timestamped, and a text form for the Copy report. */
class ErrorLogTest {
    @Test fun `newest first, capped at the capacity, stamped by the clock`() {
        var t = 0
        val log = ErrorLog(capacity = 3, clock = { "T${++t}" })
        log.record("tv", "one")
        log.record("tv", "two")
        log.record("sonos", "three")
        log.record("tv", "four")
        assertEquals(listOf("four", "three", "two"), log.entries.value.map { it.message })
        assertEquals("T4", log.entries.value.first().at)
        assertEquals("sonos", log.entries.value[1].source)
    }

    @Test fun `text is one line per entry and says so when empty`() {
        val log = ErrorLog(clock = { "2026-09-10T12:00:00" })
        assertEquals("no errors recorded", log.text())
        log.record("tv", "Can't reach the TV")
        assertEquals("2026-09-10T12:00:00  tv: Can't reach the TV", log.text())
        log.clear()
        assertEquals("no errors recorded", log.text())
    }
}
