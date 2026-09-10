package com.alec.hearthtv.protocol.roku

import com.alec.hearthtv.fakes.FakeRoku
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Stage 2b, written before RokuClient existed: ECP as the Ultra actually speaks it, including the Limited-mode
 * refusal that greeted the first probe.
 */
class RokuClientTest {
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun client(r: FakeRoku) = RokuClient(r.baseUrl, http)

    @Test fun `device info and the active app parse from the captured replies`() = runTest {
        FakeRoku().use { r ->
            val info = client(r).deviceInfo()
            assertEquals("Roku Ultra", info.modelName)
            assertEquals("4660X", info.modelNumber)
            assertEquals("Test Room Roku", info.friendlyName)
            assertEquals("15.3.4", info.softwareVersion)
            assertEquals("PowerOn", info.powerMode)
            val active = client(r).activeApp()
            assertEquals("773622", active.id)
            assertEquals("Backdrops", active.name)
            assertFalse(active.isHome)
        }
    }

    @Test fun `the app list is the real 61 channels with entities decoded`() = runTest {
        FakeRoku().use { r ->
            val apps = client(r).apps()
            assertEquals(61, apps.size)
            assertEquals("Prime Video", apps.first { it.id == "13" }.name)
            assertTrue(apps.any { it.name == "Plex - Free Movies & TV" })
            assertTrue(apps.all { it.id.isNotBlank() && it.name.isNotBlank() })
        }
    }

    @Test fun `keypress and launch reach the box, and an unknown key is a bad response rather than a crash`() = runTest {
        FakeRoku().use { r ->
            val c = client(r)
            c.keypress("Home")
            c.keypress("Select")
            assertEquals(listOf("Home", "Select"), r.keys)
            c.launch("13")
            assertEquals("Prime Video", r.activeAppName)
            assertEquals("Prime Video", c.activeApp().name)
            val e = runCatching { c.keypress("Bogus") }.exceptionOrNull()
            assertTrue("$e", e is RokuException.BadResponse)
            assertEquals(400, (e as RokuException.BadResponse).code)
        }
    }

    @Test fun `Limited mode still answers device info but every control call explains the fix`() = runTest {
        FakeRoku().use { r ->
            r.limitedMode = true
            val c = client(r)
            assertEquals("Roku Ultra", c.deviceInfo().modelName)
            val calls = listOf<suspend () -> Unit>({ c.apps() }, { c.keypress("Home") }, { c.launch("13") })
            for (call in calls) {
                val e = runCatching { call() }.exceptionOrNull()
                assertTrue("$e", e is RokuException.LimitedMode)
                assertTrue(e!!.message!!.contains("Control by mobile apps"))
            }
            assertTrue(r.keys.isEmpty())
        }
    }

    @Test fun `a Roku that is off the network is Unreachable`() = runTest {
        val r = FakeRoku()
        val url = r.baseUrl
        r.close()
        val e = runCatching { RokuClient(url, http).deviceInfo() }.exceptionOrNull()
        assertTrue("$e", e is RokuException.Unreachable)
    }
}
