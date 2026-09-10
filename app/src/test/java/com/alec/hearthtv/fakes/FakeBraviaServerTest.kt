package com.alec.hearthtv.fakes

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The fake's contract, pinned to what the real KD-85X80CK did on 2026-09-09/10. If a Stage 1 client test
 * ever disagrees with a contract test against the real TV, this file is what gets corrected first.
 */
class FakeBraviaServerTest {
    private lateinit var tv: FakeBraviaServer
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    @Before fun start() { tv = FakeBraviaServer() }
    @After fun stop() { tv.close() }

    private fun rpc(
        service: String, method: String, params: String = "[]", version: String = "1.0",
        cookie: String? = null, basicPin: String? = null,
    ): Pair<Int, String> {
        val body = """{"method":"$method","id":7,"params":$params,"version":"$version"}"""
        val req = Request.Builder().url("${tv.baseUrl}/sony/$service").post(body.toRequestBody(jsonType)).apply {
            if (cookie != null) header("Cookie", "auth=$cookie")
            if (basicPin != null) header("Authorization", "Basic " + Base64.getEncoder().encodeToString(":$basicPin".toByteArray()))
        }.build()
        http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private val cookie get() = tv.cookieValue

    @Test fun `power status answers active and follows setPowerStatus`() {
        assertEquals(200 to """{"result":[{"status":"active"}],"id":7}""", rpc("system", "getPowerStatus"))
        rpc("system", "setPowerStatus", """[{"status":false}]""", cookie = cookie)
        assertEquals("standby", tv.power)
        assertTrue(rpc("system", "getPowerStatus").second.contains("standby"))
    }

    @Test fun `unpaired calls to gated methods get the 403 auth_url body`() {
        val (code, body) = rpc("system", "getSystemInformation")
        assertEquals(403, code)
        assertTrue(body.contains("auth_url"))
        assertTrue(body.contains("[403,\"Forbidden\"]") || body.contains("[\n    403"))
        // ungated ones keep answering
        assertEquals(200, rpc("system", "getInterfaceInformation").first)
        assertEquals(200, rpc("audio", "getVolumeInformation").first)
        assertEquals(200, rpc("avContent", "getCurrentExternalInputsStatus").first)
        assertEquals(200, rpc("system", "getRemoteControllerInfo").first)
    }

    @Test fun `actRegister with nick instead of nickname is Illegal Argument`() {
        val (code, body) = rpc(
            "accessControl", "actRegister",
            """[{"clientid":"HearthTV:1","nick":"Hearth TV","level":"private"},[{"value":"yes","function":"WOL"}]]""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("[3,\"Illegal Argument\"]"))
        assertFalse(tv.pinShown)
    }

    @Test fun `actRegister flow shows a PIN then grants a cookie for the right PIN`() {
        val params = """[{"clientid":"HearthTV:1","nickname":"Hearth TV","level":"private"},[{"value":"yes","function":"WOL"}]]"""
        val (code1, body1) = rpc("accessControl", "actRegister", params)
        assertEquals(401, code1)
        assertTrue(body1.contains("[401,\"Unauthorized\"]"))
        assertTrue(tv.pinShown)

        val wrong = rpc("accessControl", "actRegister", params, basicPin = "0000")
        assertEquals(401, wrong.first)
        assertFalse(tv.paired)

        val req = Request.Builder().url("${tv.baseUrl}/sony/accessControl")
            .post("""{"method":"actRegister","id":13,"params":$params,"version":"1.0"}""".toRequestBody(jsonType))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(":${tv.pin}".toByteArray()))
            .build()
        http.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val setCookie = resp.header("Set-Cookie")
            assertNotNull(setCookie)
            assertTrue(setCookie!!.startsWith("auth=${tv.cookieValue}; Path=/sony/"))
        }
        assertTrue(tv.paired)
        assertFalse(tv.pinShown)
        assertEquals(200, rpc("system", "getSystemInformation", cookie = cookie).first)
    }

    @Test fun `a valid cookie renews the registration without a PIN`() {
        val params = """[{"clientid":"HearthTV:1","nickname":"Hearth TV","level":"private"},[{"value":"yes","function":"WOL"}]]"""
        val (code, _) = rpc("accessControl", "actRegister", params, cookie = cookie)
        assertEquals(200, code)
        assertFalse(tv.pinShown)
    }

    @Test fun `no PIN is shown while the TV is in standby`() {
        tv.power = "standby"
        rpc("accessControl", "actRegister", """[{"clientid":"x","nickname":"y","level":"private"},[]]""")
        assertFalse(tv.pinShown)
    }

    @Test fun `IRCC requires the cookie and records the key by name`() {
        val display = "AAAAAQAAAAEAAAA6Aw=="
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$display</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"""
        val xml = "text/xml; charset=UTF-8".toMediaType()
        val unauth = Request.Builder().url("${tv.baseUrl}/sony/ircc").post(soap.toRequestBody(xml)).build()
        http.newCall(unauth).execute().use { assertEquals(403, it.code) }

        val authed = Request.Builder().url("${tv.baseUrl}/sony/ircc").post(soap.toRequestBody(xml))
            .header("Cookie", "auth=$cookie")
            .header("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"").build()
        http.newCall(authed).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("X_SendIRCCResponse"))
        }
        assertEquals(listOf("Display"), tv.irccSent)

        val bogus = Request.Builder().url("${tv.baseUrl}/sony/ircc")
            .post(soap.replace(display, "AAAAAQAAAAEAAAAAAA==").toRequestBody(xml))
            .header("Cookie", "auth=$cookie").build()
        http.newCall(bogus).execute().use { assertEquals(500, it.code) }
    }

    @Test fun `now playing is Illegal State on the launcher and an input after setPlayContent`() {
        val home = rpc("avContent", "getPlayingContentInfo", cookie = cookie)
        assertTrue(home.second.contains("[7,\"Illegal State\"]"))
        rpc("avContent", "setPlayContent", """[{"uri":"extInput:hdmi?port=2"}]""", cookie = cookie)
        val playing = rpc("avContent", "getPlayingContentInfo", cookie = cookie).second
        assertTrue(playing.contains("\"uri\":\"extInput:hdmi?port=2\""))
        assertTrue(playing.contains("HDMI 2"))
        rpc("appControl", "setActiveApp", """[{"uri":"com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity"}]""", cookie = cookie)
        assertTrue(rpc("avContent", "getPlayingContentInfo", cookie = cookie).second.contains("Illegal State"))
    }

    @Test fun `volume accepts absolute and relative values and clamps`() {
        rpc("audio", "setAudioVolume", """[{"target":"speaker","volume":"+1"}]""", cookie = cookie)
        assertEquals(19, tv.volume)
        rpc("audio", "setAudioVolume", """[{"target":"speaker","volume":"-4"}]""", cookie = cookie)
        assertEquals(15, tv.volume)
        rpc("audio", "setAudioVolume", """[{"target":"speaker","volume":"250"}]""", cookie = cookie)
        assertEquals(100, tv.volume)
        rpc("audio", "setAudioMute", """[{"status":true}]""", cookie = cookie)
        assertTrue(tv.muted)
        assertTrue(rpc("audio", "getVolumeInformation").second.contains("\"mute\":true"))
    }

    @Test fun `sound output switches between audioSystem and speaker`() {
        assertTrue(rpc("audio", "getSoundSettings", """[{"target":"outputTerminal"}]""", "1.1").second.contains("audioSystem"))
        rpc("audio", "setSoundSettings", """[{"settings":[{"target":"outputTerminal","value":"speaker"}]}]""", "1.1", cookie = cookie)
        assertEquals("speaker", tv.outputTerminal)
    }

    @Test fun `unknown methods answer No Such Method`() {
        assertTrue(rpc("system", "getFooBar").second.contains("[12,\"No Such Method\"]"))
    }
}
