package com.alec.hearthtv.fakes

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A stateful stand-in for the Sonos Arc that coordinates the "TV Room" home-theatre group (Arc + Sub + two
 * Era 300 satellites). Speaks the UPnP SOAP subset the app needs, with envelopes shaped like the fixtures
 * captured 2026-09-10 (the sonos fixtures folder). Bonded satellites are invisible members, so RenderingControl on
 * the coordinator IS the whole system's volume — exactly as on the real set.
 */
class FakeSonos : AutoCloseable {
    val server = MockWebServer()
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    // ── observable state ────────────────────────────────────────────────────────────────────────────
    var volume: Int = 17
    var muted: Boolean = false
    var nightMode: Boolean = false
    var dialogLevel: Boolean = true          // "speech enhancement"
    var transportState: String = "PLAYING"   // PLAYING | PAUSED_PLAYBACK | STOPPED
    var currentUri: String = TV_INPUT_URI
    val calls = mutableListOf<String>()      // "service#action"

    companion object {
        const val COORDINATOR_UUID = "RINCON_000000000AA101400"
        const val TV_INPUT_URI = "x-sonos-htastream:$COORDINATOR_UUID:spdif"
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    private var closed = false
    override fun close() {
        if (!closed) { closed = true; server.shutdown() }
    }

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.path ?: return MockResponse().setResponseCode(404)
        if (req.method == "GET" && path == "/xml/device_description.xml") {
            return xml(200, Fixtures.text("sonos/sonos_device_description.xml"))
        }
        if (req.method != "POST") return MockResponse().setResponseCode(404)
        val action = req.getHeader("SOAPACTION")?.trim('"')?.substringAfter("#")
            ?: return fault(401, "Invalid Action")
        val body = req.body.readUtf8()
        fun arg(name: String): String? =
            body.substringAfter("<$name>", "").substringBefore("</$name>", "").takeIf { it.isNotEmpty() }
        val service = path.removePrefix("/MediaRenderer/").removePrefix("/").substringBefore("/")
        calls += "$service#$action"

        return when (path) {
            "/MediaRenderer/RenderingControl/Control" -> when (action) {
                "GetVolume" -> ok(service, action, "<CurrentVolume>$volume</CurrentVolume>")
                "SetVolume" -> { volume = arg("DesiredVolume")!!.toInt().coerceIn(0, 100); ok(service, action, "") }
                "SetRelativeVolume" -> {
                    volume = (volume + arg("Adjustment")!!.toInt()).coerceIn(0, 100)
                    ok(service, action, "<NewVolume>$volume</NewVolume>")
                }
                "GetMute" -> ok(service, action, "<CurrentMute>${muted.bit()}</CurrentMute>")
                "SetMute" -> { muted = arg("DesiredMute") == "1"; ok(service, action, "") }
                "GetEQ" -> when (arg("EQType")) {
                    "NightMode" -> ok(service, action, "<CurrentValue>${nightMode.bit()}</CurrentValue>")
                    "DialogLevel" -> ok(service, action, "<CurrentValue>${dialogLevel.bit()}</CurrentValue>")
                    else -> fault(402, "Invalid Args")
                }
                "SetEQ" -> {
                    val on = arg("DesiredValue") == "1"
                    when (arg("EQType")) {
                        "NightMode" -> nightMode = on
                        "DialogLevel" -> dialogLevel = on
                        else -> return fault(402, "Invalid Args")
                    }
                    ok(service, action, "")
                }
                else -> fault(401, "Invalid Action")
            }
            "/MediaRenderer/GroupRenderingControl/Control" -> when (action) {
                "GetGroupVolume" -> ok(service, action, "<CurrentVolume>$volume</CurrentVolume>")
                "SetGroupVolume" -> { volume = arg("DesiredVolume")!!.toInt().coerceIn(0, 100); ok(service, action, "") }
                "SetRelativeGroupVolume" -> {
                    volume = (volume + arg("Adjustment")!!.toInt()).coerceIn(0, 100)
                    ok(service, action, "<NewVolume>$volume</NewVolume>")
                }
                "GetGroupMute" -> ok(service, action, "<CurrentMute>${muted.bit()}</CurrentMute>")
                "SetGroupMute" -> { muted = arg("DesiredMute") == "1"; ok(service, action, "") }
                else -> fault(401, "Invalid Action")
            }
            "/MediaRenderer/AVTransport/Control" -> when (action) {
                "GetTransportInfo" -> ok(
                    service, action,
                    "<CurrentTransportState>$transportState</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>",
                )
                "GetMediaInfo" -> ok(
                    service, action,
                    "<NrTracks>1</NrTracks><MediaDuration>NOT_IMPLEMENTED</MediaDuration>" +
                        "<CurrentURI>$currentUri</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>" +
                        "<NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium>" +
                        "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
                )
                "GetPositionInfo" -> xml(200, Fixtures.text("sonos/sonos_GetPositionInfo.xml"))
                "SetAVTransportURI" -> { currentUri = arg("CurrentURI") ?: ""; ok(service, action, "") }
                "Play" -> { transportState = "PLAYING"; ok(service, action, "") }
                "Pause" -> { transportState = "PAUSED_PLAYBACK"; ok(service, action, "") }
                "Stop" -> { transportState = "STOPPED"; ok(service, action, "") }
                else -> fault(401, "Invalid Action")
            }
            "/ZoneGroupTopology/Control" -> when (action) {
                "GetZoneGroupState" -> xml(200, Fixtures.text("sonos/sonos_GetZoneGroupState.xml"))
                "GetZoneGroupAttributes" -> xml(200, Fixtures.text("sonos/sonos_GetZoneGroupAttributes.xml"))
                else -> fault(401, "Invalid Action")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun Boolean.bit() = if (this) 1 else 0

    private fun ok(service: String, action: String, inner: String) = xml(
        200,
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$inner</u:${action}Response></s:Body></s:Envelope>""",
    )

    private fun fault(code: Int, description: String) = xml(
        500,
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>""" +
            """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode>""" +
            """<errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
    )

    private fun xml(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "text/xml; charset=\"utf-8\"").setBody(body.trim())
}
