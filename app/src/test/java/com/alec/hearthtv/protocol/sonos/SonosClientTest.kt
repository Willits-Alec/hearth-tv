package com.alec.hearthtv.protocol.sonos

import com.alec.hearthtv.fakes.FakeSonos
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Stage 2a, written before the client existed. FakeSonos replays the Arc's replies captured 2026-09-10; the
 * topology fixture is the whole house (TV Room, Shop, CraftRoom pair, Living Room, Office) so the client has to
 * find the home-theatre group among several.
 */
class SonosClientTest {
    private lateinit var arc: FakeSonos
    private lateinit var client: SonosClient
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before fun start() {
        arc = FakeSonos()
        client = SonosClient(arc.baseUrl, http)
    }

    @After fun stop() = arc.close()

    @Test fun `device description names the model, room, software and player id`() = runTest {
        val d = client.description()
        assertEquals("Sonos Arc", d.modelName)
        assertEquals("TV Room", d.roomName)
        assertEquals("94.1-76070", d.softwareVersion)
        assertEquals(FakeSonos.COORDINATOR_UUID, d.uuid)
        assertTrue(d.isHomeTheatre)          // zoneType 21 = home-theatre primary
    }

    @Test fun `volume read, set, step and mute`() = runTest {
        assertEquals(17, client.volume())
        client.setVolume(40)
        assertEquals(40, arc.volume)
        assertEquals(37, client.volumeStep(-3))
        assertEquals(37, arc.volume)
        assertEquals(100, client.setVolume(140).also { }.let { arc.volume })   // clamped by the player
        assertFalse(client.muted())
        client.setMute(true)
        assertTrue(arc.muted)
        assertTrue(client.muted())
    }

    @Test fun `night sound and speech enhancement round-trip`() = runTest {
        assertFalse(client.nightMode())
        assertTrue(client.speechEnhancement())
        client.setNightMode(true)
        client.setSpeechEnhancement(false)
        assertTrue(arc.nightMode)
        assertFalse(arc.dialogLevel)
        assertTrue(client.nightMode())
        assertFalse(client.speechEnhancement())
    }

    @Test fun `transport state follows play and pause`() = runTest {
        assertEquals(TransportState.PLAYING, client.transportState())
        client.pause()
        assertEquals(TransportState.PAUSED, client.transportState())
        client.play()
        assertEquals(TransportState.PLAYING, client.transportState())
    }

    @Test fun `source is the TV input by default and becomes the queue after a change`() = runTest {
        val src = client.source()
        assertEquals(SourceKind.TV, src.kind)
        assertEquals(FakeSonos.TV_INPUT_URI, src.uri)
        arc.currentUri = "x-rincon-queue:${FakeSonos.COORDINATOR_UUID}#0"
        assertEquals(SourceKind.QUEUE, client.source().kind)
        arc.currentUri = "x-sonosapi-radio:st%3a1234?sid=254"
        assertEquals(SourceKind.STREAM, client.source().kind)
    }

    @Test fun `switchToTv puts the player on its own optical-in stream and plays`() = runTest {
        arc.currentUri = "x-rincon-queue:${FakeSonos.COORDINATOR_UUID}#0"
        arc.transportState = "STOPPED"
        client.switchToTv()
        assertEquals(FakeSonos.TV_INPUT_URI, arc.currentUri)
        assertEquals("PLAYING", arc.transportState)
        assertEquals(SourceKind.TV, client.source().kind)
    }

    @Test fun `zone groups parse the whole house and find the home-theatre group`() = runTest {
        val groups = client.zoneGroups()
        assertEquals(5, groups.size)
        val tvRoom = groups.first { it.name == "TV Room" }
        assertEquals(FakeSonos.COORDINATOR_UUID, tvRoom.coordinatorUuid)
        assertEquals(1, tvRoom.memberUuids.size)                     // the Arc is the only visible member
        assertEquals(3, tvRoom.satelliteUuids.size)                  // Sub + two Era 300
        assertTrue(tvRoom.satelliteUuids.containsAll(listOf("RINCON_000000000AA201400", "RINCON_000000000AA301400", "RINCON_000000000AA501400")))
        assertEquals("192.0.2.208", tvRoom.coordinatorHost)
        val pair = groups.first { it.name == "CraftRoom" }
        assertEquals(2, pair.memberUuids.size)                       // a stereo pair shows both players
        assertEquals(tvRoom, client.homeTheatreGroup())
    }

    @Test fun `UPnP faults surface with their error code`() = runTest {
        try {
            client.soap("MediaRenderer/RenderingControl", "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>Bogus</EQType>")
            fail("expected UpnpError")
        } catch (e: SonosException.UpnpError) {
            assertEquals(402, e.code)
            assertEquals("Invalid Args", e.description)
        }
    }

    @Test fun `an unreachable player surfaces as Unreachable`() = runTest {
        arc.close()
        try {
            SonosClient(arc.baseUrl, http).volume()
            fail("expected Unreachable")
        } catch (_: SonosException.Unreachable) { }
    }
}
