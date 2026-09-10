package com.alec.hearthtv.diagnostics

import com.alec.hearthtv.fakes.FakeBraviaServer
import com.alec.hearthtv.fakes.FakeSonos
import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaCredentials
import com.alec.hearthtv.protocol.sonos.SonosClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The Self-test is the contract suite compiled into the app: the same read-only checks, run on the phone with
 * one tap, producing a report the owner can paste back. These tests pin the checks and the report format.
 */
class SelfTestTest {
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Test fun `everything green on a paired TV with a Sonos`() = runTest {
        FakeBraviaServer().use { tv -> FakeSonos().use { arc ->
            val report = SelfTest(
                tv = BraviaClient(tv.baseUrl, http, BraviaCredentials.Cookie(tv.cookieValue, null)),
                sonos = SonosClient(arc.baseUrl, http),
                appVersion = "0.1.0 (3)",
            ).run()
            assertTrue(report.text(), report.allPassed)
            val names = report.checks.map { it.name }
            assertTrue(names.containsAll(listOf("TV reachable", "TV paired", "TV power", "TV inputs", "TV volume", "TV sound output", "TV remote codes", "Sonos reachable", "Sonos group")))
            val text = report.text()
            assertTrue(text.startsWith("Hearth TV self-test 0.1.0 (3)"))
            assertTrue(text.contains("PASS  TV reachable — KD-85X80CK"))
            assertTrue(text.contains("PASS  Sonos reachable — Sonos Arc"))
        } }
    }

    @Test fun `an unpaired TV fails exactly the pairing check and what depends on it`() = runTest {
        FakeBraviaServer().use { tv ->
            val report = SelfTest(tv = BraviaClient(tv.baseUrl, http), sonos = null, appVersion = "t").run()
            assertFalse(report.allPassed)
            val byName = report.checks.associateBy { it.name }
            assertTrue(byName.getValue("TV reachable").passed)
            assertFalse(byName.getValue("TV paired").passed)
            assertTrue(byName.getValue("TV paired").detail.contains("pair"))
            assertTrue(byName.getValue("TV power").passed)             // ungated
            assertEquals("not configured", byName.getValue("Sonos reachable").detail)
            assertTrue(report.text().contains("FAIL  TV paired"))
        }
    }

    @Test fun `an unreachable TV fails fast and skips the dependent checks`() = runTest {
        val tv = FakeBraviaServer()
        val url = tv.baseUrl
        tv.close()
        val report = SelfTest(tv = BraviaClient(url, http), sonos = null, appVersion = "t").run()
        assertFalse(report.allPassed)
        val reachable = report.checks.first { it.name == "TV reachable" }
        assertFalse(reachable.passed)
        assertTrue(report.checks.filter { it.name.startsWith("TV ") && it.name != "TV reachable" }.all { it.detail == "skipped" })
    }
}
