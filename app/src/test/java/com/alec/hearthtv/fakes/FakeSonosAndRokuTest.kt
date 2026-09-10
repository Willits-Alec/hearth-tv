package com.alec.hearthtv.fakes

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSonosAndRokuTest {
    private val http = OkHttpClient()
    private val xml = "text/xml; charset=\"utf-8\"".toMediaType()

    private fun soap(base: String, path: String, service: String, action: String, args: String): Pair<Int, String> {
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$args</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder().url(base + path).post(body.toRequestBody(xml))
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"").build()
        http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun get(url: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(url).get().build()).execute().use { it.code to (it.body?.string() ?: "") }

    private fun post(url: String): Int =
        http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()).execute().use { it.code }

    // ── Sonos ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `sonos volume, relative volume and mute round-trip through RenderingControl`() = FakeSonos().use { s ->
        val rc = "/MediaRenderer/RenderingControl/Control"
        assertTrue(soap(s.baseUrl, rc, "RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>").second.contains("<CurrentVolume>17</CurrentVolume>"))
        soap(s.baseUrl, rc, "RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>25</DesiredVolume>")
        assertEquals(25, s.volume)
        val (_, rel) = soap(s.baseUrl, rc, "RenderingControl", "SetRelativeVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><Adjustment>-3</Adjustment>")
        assertTrue(rel.contains("<NewVolume>22</NewVolume>"))
        soap(s.baseUrl, rc, "RenderingControl", "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>")
        assertTrue(s.muted)
        assertTrue(soap(s.baseUrl, rc, "RenderingControl", "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>").second.contains("<CurrentMute>1</CurrentMute>"))
    }

    @Test fun `sonos night mode and speech enhancement are EQ settings`() = FakeSonos().use { s ->
        val rc = "/MediaRenderer/RenderingControl/Control"
        assertTrue(soap(s.baseUrl, rc, "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>NightMode</EQType>").second.contains("<CurrentValue>0</CurrentValue>"))
        soap(s.baseUrl, rc, "RenderingControl", "SetEQ", "<InstanceID>0</InstanceID><EQType>NightMode</EQType><DesiredValue>1</DesiredValue>")
        assertTrue(s.nightMode)
        assertTrue(soap(s.baseUrl, rc, "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>DialogLevel</EQType>").second.contains("<CurrentValue>1</CurrentValue>"))
        val (code, body) = soap(s.baseUrl, rc, "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>Bogus</EQType>")
        assertEquals(500, code)
        assertTrue(body.contains("<errorCode>402</errorCode>"))
    }

    @Test fun `sonos source is the TV input by default and follows SetAVTransportURI`() = FakeSonos().use { s ->
        val av = "/MediaRenderer/AVTransport/Control"
        assertTrue(soap(s.baseUrl, av, "AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>").second.contains("<CurrentURI>${FakeSonos.TV_INPUT_URI}</CurrentURI>"))
        soap(s.baseUrl, av, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>x-rincon-queue:${FakeSonos.COORDINATOR_UUID}#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        assertTrue(s.currentUri.startsWith("x-rincon-queue:"))
        soap(s.baseUrl, av, "AVTransport", "Pause", "<InstanceID>0</InstanceID>")
        assertTrue(soap(s.baseUrl, av, "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>").second.contains("PAUSED_PLAYBACK"))
    }

    @Test fun `sonos topology fixture names the coordinator and the satellites`() = FakeSonos().use { s ->
        val (code, body) = soap(s.baseUrl, "/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState", "")
        assertEquals(200, code)
        assertTrue(body.contains("Coordinator=&quot;${FakeSonos.COORDINATOR_UUID}&quot;"))
        assertTrue(body.contains("ZoneName=&quot;TV Room&quot;"))
        assertTrue(body.contains("HTSatChanMapSet"))
        assertTrue(get(s.baseUrl + "/xml/device_description.xml").second.contains("<modelName>Sonos Arc</modelName>"))
    }

    // ── Roku ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `roku in Limited mode answers device info and active app but refuses control`() = FakeRoku().use { r ->
        val (code, info) = get(r.baseUrl + "/query/device-info")
        assertEquals(200, code)
        assertTrue(info.contains("<model-number>4660X</model-number>"))
        assertTrue(get(r.baseUrl + "/query/active-app").second.contains("Backdrops"))
        val (appsCode, appsBody) = get(r.baseUrl + "/query/apps")
        assertEquals(403, appsCode)
        assertEquals(FakeRoku.LIMITED_MESSAGE, appsBody)
        assertEquals(403, post(r.baseUrl + "/keypress/Home"))
        assertTrue(r.keys.isEmpty())
    }

    @Test fun `roku in Default mode takes keypresses and launches apps`() = FakeRoku().use { r ->
        r.limitedMode = false
        assertEquals(200, post(r.baseUrl + "/keypress/Home"))
        assertEquals(200, post(r.baseUrl + "/keypress/Lit_a"))
        assertEquals(400, post(r.baseUrl + "/keypress/Bogus"))
        assertEquals(listOf("Home", "Lit_a"), r.keys)
        assertTrue(get(r.baseUrl + "/query/apps").second.contains("""<app id="12" type="appl" version="1.0">Netflix</app>"""))
        assertEquals(200, post(r.baseUrl + "/launch/12"))
        assertEquals("Netflix", r.activeAppName)
        assertEquals(404, post(r.baseUrl + "/launch/999999"))
    }
}
