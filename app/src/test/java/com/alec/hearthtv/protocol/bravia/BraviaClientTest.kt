package com.alec.hearthtv.protocol.bravia

import com.alec.hearthtv.fakes.FakeBraviaServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Stage 1, written before the client existed. Every expectation here is something the real KD-85X80CK did
 * on 2026-09-09/10 (see fixtures/README.md), replayed by FakeBraviaServer.
 */
class BraviaClientTest {
    private lateinit var tv: FakeBraviaServer
    private lateinit var client: BraviaClient
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before fun start() {
        tv = FakeBraviaServer()
        client = BraviaClient(tv.baseUrl, http)
    }

    @After fun stop() = tv.close()

    private fun pair() {
        client.credentials = BraviaCredentials.Cookie(tv.cookieValue, expiresAtEpochMs = null)
    }

    // ── no pairing needed ──────────────────────────────────────────────────────────────────────────

    @Test fun `interface info names the model`() = runTest {
        val info = client.interfaceInfo()
        assertEquals("KD-85X80CK", info.modelName)
        assertEquals("5.7.0", info.interfaceVersion)
    }

    @Test fun `power status reads active and standby`() = runTest {
        assertEquals(PowerState.ACTIVE, client.powerStatus())
        tv.power = "standby"
        assertEquals(PowerState.STANDBY, client.powerStatus())
    }

    @Test fun `volume info parses level, mute and range`() = runTest {
        val v = client.volume()
        assertEquals(18, v.level)
        assertFalse(v.muted)
        assertEquals(0, v.min)
        assertEquals(100, v.max)
    }

    @Test fun `sound output reads the audio system`() = runTest {
        assertEquals(SoundOutput.AUDIO_SYSTEM, client.soundOutput())
    }

    @Test fun `inputs carry the owner's labels and the eARC port`() = runTest {
        val inputs = client.inputs()
        assertEquals(5, inputs.size)
        val game = inputs.first { it.uri == "extInput:hdmi?port=2" }
        assertEquals("Game", game.label)
        assertEquals("Game", game.displayName)
        val arc = inputs.first { it.uri == "extInput:hdmi?port=3" }
        assertTrue(arc.connected)
        assertEquals("HDMI 3 (eARC/ARC)", arc.displayName)
        assertEquals("Roku", inputs.first { it.uri == "extInput:hdmi?port=4" }.displayName)
    }

    @Test fun `remote codes come from the TV, not a hard-coded table`() = runTest {
        val codes = client.remoteCodes()
        assertEquals(152, codes.size)
        assertEquals("AAAAAQAAAAEAAAA6Aw==", codes["Display"])
        assertTrue(codes.containsKey("Netflix"))
        // second call is served from the cache: still one request to the TV
        client.remoteCodes()
        assertEquals(1, tv.calls.count { it.body.toString().isNotEmpty() && it.path == "/sony/system" })
    }

    // ── gating ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `gated methods throw AuthRequired with the pairing URL before pairing`() = runTest {
        try {
            client.systemInfo()
            fail("expected AuthRequired")
        } catch (e: BraviaException.AuthRequired) {
            assertTrue(e.authUrl!!.endsWith("/sony/webauth/auth_default"))
        }
    }

    @Test fun `IRCC before pairing is AuthRequired, not a silent no-op`() = runTest {
        try {
            client.sendKey("Display")
            fail("expected AuthRequired")
        } catch (_: BraviaException.AuthRequired) { }
        assertTrue(tv.irccSent.isEmpty())
    }

    // ── pairing ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `pairing flow - PIN shown, wrong PIN rejected, right PIN yields a cookie that works`() = runTest {
        assertEquals(PairingStart.PIN_SHOWN, client.startPairing("HearthTV:test", "Hearth TV"))
        assertTrue(tv.pinShown)
        try {
            client.completePairing("HearthTV:test", "Hearth TV", "0000")
            fail("expected WrongPin")
        } catch (_: BraviaException.WrongPin) { }
        val cookie = client.completePairing("HearthTV:test", "Hearth TV", tv.pin)
        assertEquals(tv.cookieValue, cookie.value)
        assertTrue(client.credentials is BraviaCredentials.Cookie)
        assertEquals("KD-85X80CK", client.systemInfo().model)
    }

    @Test fun `starting pairing while already paired renews silently`() = runTest {
        pair()
        assertEquals(PairingStart.ALREADY_PAIRED, client.startPairing("HearthTV:test", "Hearth TV"))
        assertFalse(tv.pinShown)
    }

    // ── paired behaviour ───────────────────────────────────────────────────────────────────────────

    @Test fun `system info and WoL mode after pairing`() = runTest {
        pair()
        val info = client.systemInfo()
        assertEquals("KD-85X80CK", info.model)
        assertEquals("02:00:00:00:00:85", info.macAddr)
        assertTrue(client.wolMode())
    }

    @Test fun `power off and on through the API`() = runTest {
        pair()
        client.setPower(false)
        assertEquals("standby", tv.power)
        client.setPower(true)
        assertEquals("active", tv.power)
    }

    @Test fun `volume set, step and mute`() = runTest {
        pair()
        client.setVolume(30)
        assertEquals(30, tv.volume)
        client.volumeStep(+2)
        assertEquals(32, tv.volume)
        client.volumeStep(-5)
        assertEquals(27, tv.volume)
        client.setMute(true)
        assertTrue(tv.muted)
        assertTrue(client.volume().muted)
    }

    @Test fun `sound output switches to TV speakers and back`() = runTest {
        pair()
        client.setSoundOutput(SoundOutput.TV_SPEAKER)
        assertEquals("speaker", tv.outputTerminal)
        assertEquals(SoundOutput.TV_SPEAKER, client.soundOutput())
        client.setSoundOutput(SoundOutput.AUDIO_SYSTEM)
        assertEquals("audioSystem", tv.outputTerminal)
    }

    @Test fun `apps list parses 37 apps with launch URIs, launch switches the active app`() = runTest {
        pair()
        val apps = client.apps()
        assertEquals(37, apps.size)
        val prime = apps.first { it.title == "Prime Video" }
        assertTrue(prime.uri.startsWith("com.sony.dtv.com.amazon.amazonvideo"))
        assertNotNull(prime.icon)
        client.launchApp(prime.uri)
        assertEquals(prime.uri, tv.activeAppUri)
    }

    @Test fun `now playing is null on the launcher and an input after switching`() = runTest {
        pair()
        assertNull(client.nowPlaying())
        client.switchInput("extInput:hdmi?port=4")
        val np = client.nowPlaying()
        assertNotNull(np)
        assertEquals("extInput:hdmi?port=4", np!!.uri)
        assertEquals("HDMI 4", np.title)
    }

    @Test fun `sendKey resolves names and aliases through the IRCC table`() = runTest {
        pair()
        client.sendKey("Display")
        client.sendKey("Back")     // alias for the table's "Return"
        client.sendKey("OK")       // alias for "Confirm"
        assertEquals(listOf("Display", "Return", "Confirm"), tv.irccSent)
        try {
            client.sendKey("NoSuchKey")
            fail("expected UnknownKey")
        } catch (_: BraviaException.UnknownKey) { }
    }

    @Test fun `typeText lands in the TV's text field`() = runTest {
        pair()
        client.typeText("dune part two")
        assertEquals("dune part two", tv.lastTextForm)
    }

    // ── error mapping ──────────────────────────────────────────────────────────────────────────────

    @Test fun `RPC errors surface with Sony's code and reason`() = runTest {
        pair()
        tv.activeInputUri = null
        try {
            client.rpc("system", "getFooBar")
            fail("expected RpcError")
        } catch (e: BraviaException.RpcError) {
            assertEquals(12, e.code)
            assertEquals("No Such Method", e.reason)
        }
    }

    @Test fun `an unreachable TV surfaces as Unreachable, quickly`() = runTest {
        tv.close()   // port now closed
        val dead = BraviaClient(tv.baseUrl, http)
        try {
            dead.powerStatus()
            fail("expected Unreachable")
        } catch (_: BraviaException.Unreachable) { }
    }
}
