package com.alec.hearthtv.remote

import com.alec.hearthtv.diagnostics.ErrorLog
import com.alec.hearthtv.fakes.FakeBraviaServer
import com.alec.hearthtv.fakes.FakeRoku
import com.alec.hearthtv.fakes.FakeSonos
import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaCredentials
import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.protocol.roku.RokuClient
import com.alec.hearthtv.protocol.roku.RokuException
import com.alec.hearthtv.protocol.sonos.SonosClient
import com.alec.hearthtv.protocol.sonos.SourceKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Stage 3, written before RemoteController existed. The controller is the pure-Kotlin brain behind the Remote
 * screen: it owns the state the UI renders and routes every button to the right device. It is exercised here
 * end to end through the real clients against the fakes, so a passing suite means the wire protocol, the
 * parsing and the routing all agree.
 */
class RemoteControllerTest {
    private lateinit var tv: FakeBraviaServer
    private lateinit var arc: FakeSonos
    private lateinit var roku: FakeRoku
    private lateinit var creds: InMemoryCredentialStore
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val wolSent = mutableListOf<String>()
    private val errors = ErrorLog(clock = { "T" })

    @Before fun start() {
        tv = FakeBraviaServer()
        arc = FakeSonos()
        roku = FakeRoku()
        creds = InMemoryCredentialStore()
    }

    @After fun stop() { tv.close(); arc.close(); roku.close() }

    private fun controller(paired: Boolean = true, withSonos: Boolean = true, withRoku: Boolean = false): RemoteController {
        if (paired) creds.saved = BraviaCredentials.Cookie(tv.cookieValue, null)
        val bravia = BraviaClient(tv.baseUrl, http, creds.saved)
        return RemoteController(
            tv = bravia,
            sonos = if (withSonos) SonosClient(arc.baseUrl, http) else null,
            credentials = creds,
            tvMac = "02:00:00:00:00:85",
            wakeOnLan = { mac -> wolSent += mac },
            pollDelayMs = 5,
            powerOnTimeoutMs = 2000,
            errors = errors,
            roku = if (withRoku) RokuClient(roku.baseUrl, http) else null,
        )
    }

    // ── reading state ──────────────────────────────────────────────────────────────────────────────

    @Test fun `refresh on a paired, running TV fills the whole screen state`() = runTest {
        tv.activeInputUri = "extInput:cec?type=player&port=2&logicalAddr=4"
        val c = controller()
        c.refresh()
        val s = c.state.value
        val on = s.tv as TvState.On
        assertEquals("Roku Ultra", on.nowPlaying)
        assertEquals(18, on.volume)
        assertFalse(on.muted)
        assertEquals(SoundOutput.AUDIO_SYSTEM, on.output)
        assertEquals(7, on.inputs.size)
        assertEquals(37, on.apps.size)
        assertEquals(PairingState.Paired, s.pairing)
        val sonos = s.sonos as SonosState.Ready
        assertEquals(17, sonos.volume)
        assertEquals(SourceKind.TV, sonos.source)
        assertEquals(VolumeTarget.SONOS, s.volumeTarget)
        assertNull(s.lastError)
    }

    @Test fun `refresh on a TV in standby is Standby, not an error`() = runTest {
        tv.power = "standby"
        val c = controller()
        c.refresh()
        assertEquals(TvState.Standby, c.state.value.tv)
        assertNull(c.state.value.lastError)
    }

    @Test fun `an unreachable TV explains itself in Wi-Fi terms`() = runTest {
        val c = controller()
        tv.close()
        c.refresh()
        val u = c.state.value.tv as TvState.Unreachable
        assertTrue(u.hint.contains("Wi-Fi"))
    }

    @Test fun `an unpaired TV still shows what it can and asks for pairing`() = runTest {
        val c = controller(paired = false)
        c.refresh()
        val s = c.state.value
        assertTrue(s.tv is TvState.On)                       // power, volume, inputs are ungated
        assertEquals(PairingState.NeedsPairing(pinShown = false), s.pairing)
    }

    // ── volume routing (D4) ────────────────────────────────────────────────────────────────────────

    @Test fun `volume goes to the Sonos while the TV is on Audio system, to the TV otherwise`() = runTest {
        val c = controller()
        c.refresh()
        c.volumeUp()
        assertEquals(18, arc.volume)
        assertEquals(18, tv.volume)                          // untouched
        c.setOutput(SoundOutput.TV_SPEAKER)
        assertEquals(VolumeTarget.TV, c.state.value.volumeTarget)
        c.volumeUp()
        assertEquals(19, tv.volume)
        assertEquals(18, arc.volume)
        c.volumeDown()
        assertEquals(18, tv.volume)
    }

    @Test fun `mute follows the same routing and the state reflects it`() = runTest {
        val c = controller()
        c.refresh()
        c.toggleMute()
        assertTrue(arc.muted)
        assertFalse(tv.muted)
        assertTrue((c.state.value.sonos as SonosState.Ready).muted)
        c.toggleMute()
        assertFalse(arc.muted)
    }

    @Test fun `without a Sonos the TV takes the volume even on Audio system`() = runTest {
        val c = controller(withSonos = false)
        c.refresh()
        assertEquals(SonosState.Absent, c.state.value.sonos)
        assertEquals(VolumeTarget.TV, c.state.value.volumeTarget)
        c.volumeUp()
        assertEquals(19, tv.volume)
    }

    // ── power ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `power toggle from standby wakes the TV and waits for it`() = runTest {
        tv.power = "standby"
        val c = controller()
        c.refresh()
        c.powerToggle()
        assertEquals(listOf("02:00:00:00:00:85"), wolSent)
        assertEquals("active", tv.power)
        assertTrue(c.state.value.tv is TvState.On)
    }

    @Test fun `power toggle from on puts the TV in standby`() = runTest {
        val c = controller()
        c.refresh()
        c.powerToggle()
        assertEquals("standby", tv.power)
        assertEquals(TvState.Standby, c.state.value.tv)
    }

    // ── actions ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `inputs, apps, keys and typing reach the TV`() = runTest {
        val c = controller()
        c.refresh()
        c.selectInput("extInput:hdmi?port=4")
        assertEquals("extInput:hdmi?port=4", tv.activeInputUri)
        assertEquals("HDMI 4", (c.state.value.tv as TvState.On).nowPlaying)
        val prime = (c.state.value.tv as TvState.On).apps.first { it.title == "Prime Video" }
        c.launchApp(prime.uri)
        assertEquals(prime.uri, tv.activeAppUri)
        assertEquals("Prime Video", (c.state.value.tv as TvState.On).nowPlaying)   // remembered: Sony reports nothing inside apps
        c.key("Back")
        c.key("Home")
        assertEquals(listOf("Return", "Home"), tv.irccSent)
        c.typeText("dune")
        assertNull(tv.lastTextForm)                                              // no text box on the TV yet
        assertTrue(c.state.value.lastError!!.contains("text box"))
        tv.textInputActive = true
        c.typeText("dune")
        assertEquals("dune", tv.lastTextForm)
    }

    @Test fun `fix the sound puts the TV on Audio system and the Arc on its TV input`() = runTest {
        tv.outputTerminal = "speaker"
        arc.currentUri = "x-rincon-queue:${FakeSonos.COORDINATOR_UUID}#0"
        arc.transportState = "STOPPED"
        val c = controller()
        c.refresh()
        c.fixSound()
        assertEquals("audioSystem", tv.outputTerminal)
        assertEquals(FakeSonos.TV_INPUT_URI, arc.currentUri)
        assertEquals("PLAYING", arc.transportState)
        assertEquals(SourceKind.TV, (c.state.value.sonos as SonosState.Ready).source)
        assertEquals(VolumeTarget.SONOS, c.state.value.volumeTarget)
    }

    @Test fun `night sound and speech enhancement toggle on the Arc`() = runTest {
        val c = controller()
        c.refresh()
        c.setNightMode(true)
        c.setSpeechEnhancement(false)
        assertTrue(arc.nightMode)
        assertFalse(arc.dialogLevel)
        val s = c.state.value.sonos as SonosState.Ready
        assertTrue(s.nightMode)
        assertFalse(s.speechEnhancement)
    }

    // ── pairing through the controller ─────────────────────────────────────────────────────────────

    @Test fun `pairing flow persists the cookie and unlocks the gated calls`() = runTest {
        val c = controller(paired = false)
        assertEquals(PairingState.NeedsPairing(pinShown = true), c.startPairing())
        assertTrue(tv.pinShown)
        val wrong = c.completePairing("0000")
        assertTrue(wrong is PairingState.Failed)
        assertEquals(PairingState.Paired, c.completePairing(tv.pin))
        assertTrue(creds.saved is BraviaCredentials.Cookie)
        c.refresh()
        assertEquals(PairingState.Paired, c.state.value.pairing)
        assertNotNull((c.state.value.tv as TvState.On).apps.firstOrNull())
    }

    // ── resilience (the "connect to the TV" screen after backgrounding) ────────────────────────────

    @Test fun `losing the TV after a good read keeps the last known screen and flags reconnecting`() = runTest {
        val c = controller()
        c.refresh()
        val before = c.state.value.tv as TvState.On
        tv.close()
        c.refresh()
        val s = c.state.value
        assertTrue(s.tv is TvState.Unreachable)
        assertEquals(before, s.lastKnown)                    // controls stay drawable from this
        assertTrue(s.reconnecting)
    }

    @Test fun `a quiet refresh never raises busy`() = runTest {
        val c = controller()
        var sawBusy = false
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { c.state.collect { if (it.busy) sawBusy = true } }
        c.refresh(quiet = true)
        job.cancel()
        assertFalse(sawBusy)
        assertTrue(c.state.value.tv is TvState.On)
    }

    // ── voice ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `voice commands route through the same actions`() = runTest {
        val c = controller()
        c.refresh()
        assertEquals("Volume up", c.voice("volume up"))
        assertEquals(18, arc.volume)
        assertEquals("Open Prime Video", c.voice("open prime"))
        assertTrue(tv.activeAppUri!!.contains("amazon"))
        assertEquals("Switch to Roku Ultra", c.voice("switch to roku"))
        assertTrue(tv.activeInputUri!!.startsWith("extInput:cec"))
        assertEquals("Pause", c.voice("pause"))
        assertEquals(listOf("Pause"), tv.irccSent)
        val unknown = c.voice("make me a sandwich")
        assertTrue(unknown.contains("Didn't catch"))
        assertTrue(unknown.contains("make me a sandwich"))
    }

    // ── the volume bar and the volume-through-the-TV option (§9.2, §9.3) ───────────────────────────

    @Test fun `the bar sets an absolute level on the Sonos and reports the range`() = runTest {
        val c = controller()
        c.refresh()
        assertEquals(VolumeTarget.SONOS, c.state.value.volumeTarget)
        assertEquals(0..100, c.state.value.volumeRange)
        c.setVolumeLevel(42)
        assertEquals(42, arc.volume)
        assertEquals(42, c.state.value.volumeLevel)
    }

    @Test fun `the bar clamps to the range instead of sending nonsense`() = runTest {
        val c = controller()
        c.refresh()
        c.setVolumeLevel(500)
        assertEquals(100, arc.volume)
        c.setVolumeLevel(-8)
        assertEquals(0, arc.volume)
        assertNull(c.state.value.lastError)
    }

    @Test fun `routing volume through the TV drives the TV even while its sound goes to the Arc`() = runTest {
        val c = RemoteController(
            tv = BraviaClient(tv.baseUrl, http, BraviaCredentials.Cookie(tv.cookieValue, null)),
            sonos = SonosClient(arc.baseUrl, http), credentials = creds, tvMac = null, wakeOnLan = {},
            errors = errors, volumeViaTv = true,
        )
        c.refresh()
        assertEquals(SoundOutput.AUDIO_SYSTEM, (c.state.value.tv as TvState.On).output)
        assertEquals(VolumeTarget.TV, c.state.value.volumeTarget)      // the option wins over the routing rule
        val before = arc.volume
        c.setVolumeLevel(25)
        assertEquals(25, tv.volume)
        assertEquals(before, arc.volume)                                // the app never touched the Arc
        assertEquals(25, c.state.value.volumeLevel)
    }

    @Test fun `the TV's own scale reaches the bar`() = runTest {
        val c = RemoteController(
            tv = BraviaClient(tv.baseUrl, http, BraviaCredentials.Cookie(tv.cookieValue, null)),
            sonos = null, credentials = creds, tvMac = null, wakeOnLan = {}, errors = errors, volumeViaTv = true,
        )
        c.refresh()
        val on = c.state.value.tv as TvState.On
        assertEquals(0, on.volumeMin)
        assertEquals(100, on.volumeMax)
        assertEquals(0..100, c.state.value.volumeRange)
    }

    @Test fun `a volume-only refresh picks up a change made elsewhere and stays quiet`() = runTest {
        val c = controller()
        c.refresh()
        arc.volume = 63                                                 // as if the Sonos app moved it
        var sawBusy = false
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { c.state.collect { if (it.busy) sawBusy = true } }
        c.refreshVolume()
        job.cancel()
        assertEquals(63, c.state.value.volumeLevel)
        assertFalse(sawBusy)
        assertNull(c.state.value.lastError)
    }

    // ── voice search on the TV (§9.4) ──────────────────────────────────────────────────────────────

    @Test fun `voice search types into a box that is already open`() = runTest {
        tv.textInputActive = true
        val c = controller()
        c.refresh()
        assertEquals("Search the TV for “the bear”", c.voice("search for the bear"))
        assertEquals("the bear", tv.lastTextForm)
        assertTrue(tv.irccSent.isEmpty())                               // no need to open anything
    }

    @Test fun `voice search asks the TV to open a box when the assistant key does that`() = runTest {
        tv.assistsOpensTextInput = true
        val c = controller()
        c.refresh()
        c.voice("search for jaws")
        assertEquals(listOf("Assists"), tv.irccSent)
        assertEquals("jaws", tv.lastTextForm)
        assertNull(c.state.value.lastError)
    }

    @Test fun `voice search says so plainly when no box appears`() = runTest {
        val c = controller()
        c.refresh()
        c.voice("search for jaws")
        assertEquals(listOf("Assists"), tv.irccSent)
        assertNull(tv.lastTextForm)
        assertEquals(RemoteController.NO_SEARCH_BOX, c.state.value.lastError)
    }

    // ── Roku (Stage 2b) ────────────────────────────────────────────────────────────────────────────

    @Test fun `refresh with a Roku fills its state from the box`() = runTest {
        val c = controller(withRoku = true)
        c.refresh()
        val r = c.state.value.roku as RokuState.Ready
        assertEquals("Test Room Roku", r.name)
        assertEquals("Backdrops", r.activeApp)
        assertFalse(r.onHome)
        assertEquals(61, r.apps.size)
        assertTrue(c.state.value.tv is TvState.On)
    }

    @Test fun `launching a Roku app switches the TV to the Roku first`() = runTest {
        val c = controller(withRoku = true)
        c.refresh()
        assertNull(tv.activeInputUri)                                   // the TV launcher is up
        c.rokuLaunch("13")
        assertEquals("extInput:cec?type=player&port=2&logicalAddr=4", tv.activeInputUri)
        assertEquals("Prime Video", roku.activeAppName)
        assertEquals("Prime Video", (c.state.value.roku as RokuState.Ready).activeApp)
        assertEquals("Roku Ultra", (c.state.value.tv as TvState.On).nowPlaying)
        assertNull(c.state.value.lastError)
    }

    @Test fun `Roku keys reach the box and Home brings it forward`() = runTest {
        val c = controller(withRoku = true)
        c.refresh()
        c.rokuKey("Select")
        assertNull(tv.activeInputUri)                                   // a plain key never switches inputs
        c.rokuKey("Home")
        assertEquals(listOf("Select", "Home"), roku.keys)
        assertEquals("extInput:cec?type=player&port=2&logicalAddr=4", tv.activeInputUri)
        assertTrue((c.state.value.roku as RokuState.Ready).onHome)
    }

    @Test fun `a Roku in Limited mode shows the unlock instruction and the TV keeps working`() = runTest {
        roku.limitedMode = true
        val c = controller(withRoku = true)
        c.refresh()
        val r = c.state.value.roku
        assertTrue("$r", r is RokuState.Limited)
        assertTrue((r as RokuState.Limited).hint.contains("Network access"))
        assertTrue(c.state.value.tv is TvState.On)
        c.rokuKey("Select")
        assertEquals(RokuException.LIMITED_HINT, c.state.value.lastError)
        assertTrue(roku.keys.isEmpty())
        assertEquals(1, errors.entries.value.count { it.source == "roku" && it.message.contains("Limited") })
    }

    @Test fun `a Roku off the network is Unreachable without touching the TV state`() = runTest {
        val gone = FakeRoku()
        val url = gone.baseUrl
        gone.close()
        val c = RemoteController(
            tv = BraviaClient(tv.baseUrl, http, BraviaCredentials.Cookie(tv.cookieValue, null)), sonos = null,
            credentials = creds, tvMac = null, wakeOnLan = {}, errors = errors, roku = RokuClient(url, http),
        )
        c.refresh()
        assertTrue(c.state.value.roku is RokuState.Unreachable)
        assertTrue(c.state.value.tv is TvState.On)
        assertEquals("roku", errors.entries.value.single().source)
    }

    // ── error log (Diagnostics) ────────────────────────────────────────────────────────────────────

    @Test fun `a failing action lands in the error log with a timestamp and its device`() = runTest {
        val c = controller()
        c.refresh()
        assertTrue(errors.entries.value.isEmpty())
        c.key("NoSuchKey")
        val e = errors.entries.value.single()
        assertEquals("T", e.at)
        assertEquals("tv", e.source)
        assertTrue(e.message.isNotBlank())
    }

    @Test fun `a TV that drops off the network is logged once, not on every quiet refresh`() = runTest {
        val gone = FakeBraviaServer()
        val url = gone.baseUrl
        gone.close()
        val c = RemoteController(
            tv = BraviaClient(url, http, BraviaCredentials.Cookie(gone.cookieValue, null)), sonos = null,
            credentials = creds, tvMac = null, wakeOnLan = {}, errors = errors,
        )
        repeat(3) { c.refresh(quiet = true) }
        assertTrue(c.state.value.tv is TvState.Unreachable)
        assertEquals(1, errors.entries.value.size)
        assertTrue(errors.entries.value.single().message.startsWith("unreachable"))
    }

    @Test fun `a failing action surfaces once in lastError and does not poison the state`() = runTest {
        val c = controller()
        c.refresh()
        c.key("NoSuchKey")
        assertNotNull(c.state.value.lastError)
        assertTrue(c.state.value.tv is TvState.On)
        c.refresh()
        assertNull(c.state.value.lastError)
    }
}
