package com.alec.hearthtv.fakes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Base64

/**
 * A stateful stand-in for the Sony KD-85X80CK (Android TV 12, REST interface 5.7.0).
 *
 * Every reply shape comes from the fixtures captured on 2026-09-09/10 — including the parts that bit us:
 *  - `actRegister` demands the field `nickname` (not `nick`) or answers `[3, "Illegal Argument"]`;
 *  - unpaired calls to gated methods answer `403` with an `auth_url` body;
 *  - `getPlayingContentInfo` answers `[7, "Illegal State"]` while the home screen or an app is up;
 *  - `/sony/ircc` answers `403` without the cookie and a SOAP `X_SendIRCCResponse` with it.
 *
 * Mutating methods change the state fields below so ViewModel tests can assert on outcomes, not on calls.
 */
class FakeBraviaServer : AutoCloseable {
    val server = MockWebServer()
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    // ── observable state ────────────────────────────────────────────────────────────────────────────
    var power: String = "active"                 // "active" | "standby"
    var volume: Int = 18
    var muted: Boolean = false
    var outputTerminal: String = "audioSystem"   // "audioSystem" | "speaker"
    var activeInputUri: String? = null           // null = launcher/app in front (Illegal State)
    var activeAppUri: String? = null
    var lastTextForm: String? = null
    var paired: Boolean = false
    var pinShown: Boolean = false
    var wolMode: Boolean = true
    val irccSent = mutableListOf<String>()       // IRCC key *names* received with a valid cookie
    val calls = mutableListOf<RecordedRequest>()

    val pin = "4458"
    val cookieValue = "0000000000000000000000000000000000000000"

    private val json = Json { ignoreUnknownKeys = true }
    private val irccByCode: Map<String, String> by lazy {
        val table = json.parseToJsonElement(Fixtures.text("bravia/getRemoteControllerInfo.json"))
            .jsonObject["result"]!!.jsonArray[1].jsonArray
        table.associate { it.jsonObject["value"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content }
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

    // ── dispatch ────────────────────────────────────────────────────────────────────────────────────
    private fun handle(req: RecordedRequest): MockResponse {
        calls += req
        val path = req.path ?: return MockResponse().setResponseCode(404)
        if (path == "/sony/ircc") return ircc(req)
        if (!path.startsWith("/sony/")) return MockResponse().setResponseCode(404)
        val service = path.removePrefix("/sony/")
        val body = runCatching { json.parseToJsonElement(req.body.readUtf8()).jsonObject }.getOrNull()
            ?: return jsonReply(200, """{"error":[5,"Illegal JSON"],"id":0}""")
        val method = body["method"]?.jsonPrimitive?.contentOrNull ?: return error(0, 3, "Illegal Argument")
        val id = body["id"]?.jsonPrimitive?.intOrNull ?: 1
        val params = body["params"] as? JsonArray ?: JsonArray(emptyList())

        if (service == "accessControl" && method == "actRegister") return actRegister(req, id, params)

        // Everything the real TV gates behind pairing.
        val gated = setOf(
            "getSystemInformation", "getWolMode", "setWolMode", "getNetworkSettings", "setPowerStatus",
            "getApplicationList", "setActiveApp", "setTextForm", "getTextForm", "terminateApps",
            "getPlayingContentInfo", "setPlayContent", "setAudioVolume", "setAudioMute", "setSoundSettings",
        )
        if (method in gated && !authed(req)) return jsonReply(403, Fixtures.text("bravia/error403.json"))

        return when (service to method) {
            "system" to "getPowerStatus" -> result(id, """[{"status":"$power"}]""")
            "system" to "getInterfaceInformation" -> fixture("bravia/getInterfaceInformation.json", id)
            "system" to "getSystemSupportedFunction" -> fixture("bravia/getSystemSupportedFunction.json", id)
            "system" to "getRemoteControllerInfo" -> fixture("bravia/getRemoteControllerInfo.json", id)
            "system" to "getSystemInformation" -> fixture("bravia/getSystemInformation.json", id)
            "system" to "getNetworkSettings" -> fixture("bravia/getNetworkSettings.json", id)
            "system" to "getWolMode" -> result(id, """[{"enabled":$wolMode}]""")
            "system" to "setWolMode" -> { wolMode = params.first().jsonObject["enabled"]!!.jsonPrimitive.boolean; result(id, "[]") }
            "system" to "setPowerStatus" -> {
                power = if (params.first().jsonObject["status"]!!.jsonPrimitive.boolean) "active" else "standby"
                result(id, "[]")
            }
            "audio" to "getVolumeInformation" -> result(
                id, """[[{"target":"speaker","volume":$volume,"mute":$muted,"maxVolume":100,"minVolume":0}]]""",
            )
            "audio" to "setAudioVolume" -> setAudioVolume(id, params)
            "audio" to "setAudioMute" -> { muted = params.first().jsonObject["status"]!!.jsonPrimitive.boolean; result(id, "[]") }
            "audio" to "getSoundSettings" -> result(id, """[[{"target":"outputTerminal","currentValue":"$outputTerminal"}]]""")
            "audio" to "setSoundSettings" -> {
                val settings = params.first().jsonObject["settings"]!!.jsonArray
                settings.forEach { s ->
                    if (s.jsonObject["target"]!!.jsonPrimitive.content == "outputTerminal") {
                        outputTerminal = s.jsonObject["value"]!!.jsonPrimitive.content
                    }
                }
                result(id, "[]")
            }
            "avContent" to "getCurrentExternalInputsStatus" -> fixture("bravia/getCurrentExternalInputsStatus.json", id)
            "avContent" to "setPlayContent" -> {
                activeInputUri = params.first().jsonObject["uri"]!!.jsonPrimitive.content
                activeAppUri = null
                result(id, "[]")
            }
            "avContent" to "getPlayingContentInfo" -> {
                val uri = activeInputUri ?: return error(id, 7, "Illegal State")
                val port = uri.substringAfter("port=", "")
                result(id, """[{"uri":"$uri","source":"extInput:hdmi","title":"HDMI $port"}]""")
            }
            "appControl" to "getApplicationList" -> fixture("bravia/getApplicationList.json", id)
            "appControl" to "setActiveApp" -> {
                activeAppUri = params.first().jsonObject["uri"]!!.jsonPrimitive.content
                activeInputUri = null
                result(id, "[]")
            }
            "appControl" to "setTextForm" -> { lastTextForm = params.first().jsonPrimitive.content; result(id, "[]") }
            "accessControl" to "getMethodTypes" -> fixture("bravia/accessControl_getMethodTypes.json", id)
            "guide" to "getSupportedApiInfo" -> fixture("bravia/getSupportedApiInfo.json", id)
            else -> error(id, 12, "No Such Method")
        }
    }

    private fun setAudioVolume(id: Int, params: JsonArray): MockResponse {
        val p = params.first().jsonObject
        val raw = p["volume"]!!.jsonPrimitive.content
        volume = when {
            raw.startsWith("+") -> volume + raw.drop(1).toInt()
            raw.startsWith("-") -> volume - raw.drop(1).toInt()
            else -> raw.toInt()
        }.coerceIn(0, 100)
        return result(id, "[]")
    }

    /**
     * Pairing, exactly as observed: params[0] must carry clientid + nickname + level; without credentials the
     * TV answers 401 and shows a PIN (only while on); HTTP Basic ":PIN" completes it and sets the auth cookie;
     * a valid cookie renews silently without a PIN.
     */
    private fun actRegister(req: RecordedRequest, id: Int, params: JsonArray): MockResponse {
        val first = params.firstOrNull() as? JsonObject ?: return error(id, 3, "Illegal Argument")
        if (first["clientid"] == null || first["nickname"] == null || first["level"] == null) {
            return error(id, 3, "Illegal Argument")
        }
        if (authed(req)) return grantCookie(id)
        val basic = req.getHeader("Authorization")?.removePrefix("Basic ")?.trim()
        if (basic != null) {
            val decoded = runCatching { String(Base64.getDecoder().decode(basic)) }.getOrDefault("")
            return if (decoded == ":$pin") grantCookie(id) else jsonReply(401, """{"error":[401,"Unauthorized"],"id":$id}""")
        }
        if (power == "active") pinShown = true
        return jsonReply(401, """{"error":[401,"Unauthorized"],"id":$id}""")
            .setHeader("WWW-Authenticate", "Basic realm=\"Private Page\"")
    }

    private fun grantCookie(id: Int): MockResponse {
        paired = true
        pinShown = false
        return result(id, "[]").setHeader(
            "Set-Cookie", "auth=$cookieValue; Path=/sony/; Max-Age=1209600; Expires=Thu, 24 Sep 2026 14:51:40 GMT",
        )
    }

    private fun ircc(req: RecordedRequest): MockResponse {
        if (!authed(req)) return MockResponse().setResponseCode(403)
        val body = req.body.readUtf8()
        val code = body.substringAfter("<IRCCCode>", "").substringBefore("</IRCCCode>", "")
        val name = irccByCode[code] ?: return MockResponse().setResponseCode(500)
            .setHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .setBody(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                    """<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>""" +
                    """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>800</errorCode>""" +
                    """<errorDescription>Cannot accept the IRCC Code</errorDescription></UPnPError></detail>""" +
                    """</s:Fault></s:Body></s:Envelope>""",
            )
        irccSent += name
        val fixture = Fixtures.text("bravia/ircc_Display.xml")
        val soap = fixture.substringAfter("\n\n").ifBlank { fixture.substring(fixture.indexOf("<?xml")) }
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .setBody(soap.trim())
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────
    private fun authed(req: RecordedRequest): Boolean =
        req.getHeader("Cookie")?.split(';')?.any { it.trim() == "auth=$cookieValue" } == true

    private fun fixture(path: String, id: Int): MockResponse {
        // Fixtures were captured with id 1; answer with the caller's id like the TV does.
        val text = Fixtures.text(path).replace(Regex("\"id\":\\s*\\d+"), "\"id\":$id")
        return jsonReply(200, text)
    }

    private fun result(id: Int, resultJson: String) = jsonReply(200, """{"result":$resultJson,"id":$id}""")
    private fun error(id: Int, code: Int, message: String) = jsonReply(200, """{"error":[$code,"$message"],"id":$id}""")
    private fun jsonReply(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val kotlinx.serialization.json.JsonPrimitive.boolean: Boolean
        get() = booleanOrNull ?: content.toBoolean()
}
