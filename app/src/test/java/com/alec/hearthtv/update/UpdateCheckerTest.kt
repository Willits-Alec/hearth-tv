package com.alec.hearthtv.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The in-app update banner reads docs/version.json from the public page; publish.ps1 writes it. */
class UpdateCheckerTest {
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

    private fun server(body: String, code: Int = 200) = MockWebServer().apply {
        enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
        start()
    }

    private val newer = """{"versionCode": 7, "versionName": "0.7.0",
        "apkUrl": "https://github.com/Willits-Alec/hearth-tv/releases/latest/download/hearth-tv.apk",
        "notes": "Roku tile", "publishedAt": "2026-09-20T10:00:00Z"}"""

    @Test fun `a newer versionCode is an available update with its notes and link`() = runTest {
        val s = server(newer)
        val status = UpdateChecker(http, s.url("/version.json").toString(), currentVersionCode = 1).check()
        s.shutdown()
        val a = status as UpdateStatus.Available
        assertEquals("0.7.0", a.versionName)
        assertEquals(7, a.versionCode)
        assertEquals("Roku tile", a.notes)
        assertTrue(a.apkUrl.endsWith("/hearth-tv.apk"))
    }

    @Test fun `the same or an older versionCode is up to date`() = runTest {
        val s = server(newer)
        assertEquals(UpdateStatus.UpToDate, UpdateChecker(http, s.url("/version.json").toString(), currentVersionCode = 7).check())
        s.shutdown()
        val s2 = server(newer)
        assertEquals(UpdateStatus.UpToDate, UpdateChecker(http, s2.url("/version.json").toString(), currentVersionCode = 9).check())
        s2.shutdown()
    }

    @Test fun `no network or a broken page is Unknown, never a crash`() = runTest {
        val s = server("<html>nope</html>")
        val broken = UpdateChecker(http, s.url("/version.json").toString(), currentVersionCode = 1).check()
        s.shutdown()
        assertTrue(broken is UpdateStatus.Unknown)
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/version.json").toString()
        dead.shutdown()
        assertTrue(UpdateChecker(http, url, currentVersionCode = 1).check() is UpdateStatus.Unknown)
    }
}
