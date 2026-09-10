package com.alec.hearthtv.protocol.bravia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Sony BRAVIA REST API (JSON-RPC over HTTP at `/sony/<service>`) plus IRCC-over-IP, as spoken by the
 * KD-85X80CK on interface 5.7.0. Every reply shape and error path here is pinned by BraviaClientTest against
 * FakeBraviaServer, which replays what the real set answered.
 *
 * Pass an [OkHttpClient] bound to the Wi-Fi network (see `net/LanTransport`) so a VPN on the phone cannot
 * swallow LAN traffic.
 */
class BraviaClient(
    baseUrl: String,
    private val http: OkHttpClient,
    @Volatile var credentials: BraviaCredentials = BraviaCredentials.None,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    @Volatile private var codesCache: Map<String, String>? = null

    companion object {
        private val JSON = "application/json".toMediaType()
        private val XML = "text/xml; charset=UTF-8".toMediaType()
        private const val IRCC_SOAPACTION = "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\""

        /** Friendly names the UI uses → the TV's own IRCC table names. */
        val KEY_ALIASES: Map<String, String> = mapOf(
            "back" to "Return", "ok" to "Confirm", "select" to "Confirm", "enter" to "Confirm",
            "power" to "TvPower", "guide" to "GGuide", "info" to "Display", "ff" to "Forward",
            "fastforward" to "Forward", "rew" to "Rewind", "exit" to "Exit", "menu" to "ActionMenu",
            "options" to "Options", "source" to "Input",
        )
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────────

    /** Raw JSON-RPC call; returns the `result` element or throws a [BraviaException]. */
    suspend fun rpc(
        service: String,
        method: String,
        params: JsonArray = JsonArray(emptyList()),
        version: String = "1.0",
    ): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("method", method)
            put("id", nextId.getAndIncrement())
            put("params", params)
            put("version", version)
        }
        val req = Request.Builder().url("$base/sony/$service").post(body.toString().toRequestBody(JSON))
            .also(::authHeaders).build()
        val (code, text, _) = execute(req)
        parseRpc(code, text)
    }

    private fun authHeaders(b: Request.Builder) {
        when (val c = credentials) {
            is BraviaCredentials.Cookie -> b.header("Cookie", "auth=${c.value}")
            is BraviaCredentials.Psk -> b.header("X-Auth-PSK", c.key)
            BraviaCredentials.None -> Unit
        }
    }

    private fun execute(req: Request): Triple<Int, String, Headers> = try {
        http.newCall(req).execute().use { Triple(it.code, it.body?.string() ?: "", it.headers) }
    } catch (e: IOException) {
        throw BraviaException.Unreachable(e)
    }

    private fun parseRpc(httpCode: Int, text: String): JsonElement {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: run {
                if (httpCode == 403 || httpCode == 401) throw BraviaException.AuthRequired(null)
                throw BraviaException.BadResponse("HTTP $httpCode: ${text.take(120)}")
            }
        obj["error"]?.let { err ->
            val arr = err.jsonArray
            val code = arr.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
            val reason = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "unknown"
            if (code == 403 || httpCode == 403) {
                val authUrl = obj["auth_url"]?.jsonObject?.get("default")?.jsonPrimitive?.contentOrNull
                throw BraviaException.AuthRequired(authUrl)
            }
            if (code == 401 || httpCode == 401) throw BraviaException.AuthRequired(null)
            throw BraviaException.RpcError(code, reason)
        }
        return obj["result"] ?: throw BraviaException.BadResponse("no result in ${text.take(120)}")
    }

    private fun params(vararg elements: JsonElement) = buildJsonArray { elements.forEach { add(it) } }
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.bool(key: String): Boolean {
        val p = this[key]?.jsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNull == "true")
    }

    // ── system ──────────────────────────────────────────────────────────────────────────────────────

    suspend fun interfaceInfo(): InterfaceInfo {
        val o = rpc("system", "getInterfaceInformation").jsonArray[0].jsonObject
        return InterfaceInfo(o.str("modelName"), o.str("productName"), o.str("productCategory"), o.str("interfaceVersion"))
    }

    suspend fun powerStatus(): PowerState {
        val status = rpc("system", "getPowerStatus").jsonArray[0].jsonObject.str("status")
        return if (status == "active") PowerState.ACTIVE else PowerState.STANDBY
    }

    /** Works from networked standby when the TV's Remote start / WoL mode is on; otherwise use [com.alec.hearthtv.protocol.WakeOnLan]. */
    suspend fun setPower(on: Boolean) {
        rpc("system", "setPowerStatus", params(buildJsonObject { put("status", on) }))
    }

    suspend fun systemInfo(): SystemInfo {
        val o = rpc("system", "getSystemInformation").jsonArray[0].jsonObject
        return SystemInfo(
            o.str("model"), o.str("serial"), o.str("macAddr"), o.str("name"),
            o.str("generation"), o.str("cid"), o.str("area"), o.str("region"),
        )
    }

    suspend fun wolMode(): Boolean = rpc("system", "getWolMode").jsonArray[0].jsonObject.bool("enabled")

    /** The MAC to wake the TV with, from the ungated `getSystemSupportedFunction` — available before pairing. */
    suspend fun wolMac(): String? = rpc("system", "getSystemSupportedFunction").jsonArray[0].jsonArray
        .firstOrNull { it.jsonObject.str("option") == "WOL" }?.jsonObject?.str("value")?.ifBlank { null }

    /** The TV's own IRCC table (name → base64 code), fetched once and cached. */
    suspend fun remoteCodes(): Map<String, String> {
        codesCache?.let { return it }
        val res = rpc("system", "getRemoteControllerInfo").jsonArray
        val table = res[1].jsonArray.associate { it.jsonObject.str("name") to it.jsonObject.str("value") }
        codesCache = table
        return table
    }

    // ── audio ───────────────────────────────────────────────────────────────────────────────────────

    suspend fun volume(): VolumeInfo {
        val all = rpc("audio", "getVolumeInformation").jsonArray[0].jsonArray
        val o = all.firstOrNull { it.jsonObject.str("target") == "speaker" }?.jsonObject ?: all[0].jsonObject
        return VolumeInfo(
            target = o.str("target"),
            level = o["volume"]?.jsonPrimitive?.intOrNull ?: 0,
            muted = o.bool("mute"),
            min = o["minVolume"]?.jsonPrimitive?.intOrNull ?: 0,
            max = o["maxVolume"]?.jsonPrimitive?.intOrNull ?: 100,
        )
    }

    suspend fun setVolume(level: Int) = setAudioVolume(level.toString())

    /** Relative change; Sony accepts "+1" / "-1" style values. */
    suspend fun volumeStep(delta: Int) = setAudioVolume(if (delta >= 0) "+$delta" else delta.toString())

    private suspend fun setAudioVolume(value: String) {
        rpc("audio", "setAudioVolume", params(buildJsonObject { put("target", "speaker"); put("volume", value) }))
    }

    suspend fun setMute(mute: Boolean) {
        rpc("audio", "setAudioMute", params(buildJsonObject { put("status", mute) }))
    }

    suspend fun soundOutput(): SoundOutput {
        val settings = rpc(
            "audio", "getSoundSettings", params(buildJsonObject { put("target", "outputTerminal") }), version = "1.1",
        ).jsonArray[0].jsonArray
        val current = settings.firstOrNull { it.jsonObject.str("target") == "outputTerminal" }?.jsonObject
            ?: throw BraviaException.BadResponse("no outputTerminal in getSoundSettings")
        return SoundOutput.fromWire(current.str("currentValue"))
    }

    suspend fun setSoundOutput(output: SoundOutput) {
        val settings = buildJsonObject {
            put("settings", buildJsonArray { add(buildJsonObject { put("target", "outputTerminal"); put("value", output.wire) }) })
        }
        rpc("audio", "setSoundSettings", params(settings), version = "1.1")
    }

    // ── inputs / apps / content ─────────────────────────────────────────────────────────────────────

    suspend fun inputs(): List<TvInput> =
        rpc("avContent", "getCurrentExternalInputsStatus", version = "1.1").jsonArray[0].jsonArray.map {
            val o = it.jsonObject
            TvInput(
                uri = o.str("uri"), title = o.str("title"), label = o.str("label"),
                connected = o.bool("connection"), active = o.str("status") == "true",
                icon = o["icon"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            )
        }

    suspend fun switchInput(uri: String) {
        rpc("avContent", "setPlayContent", params(buildJsonObject { put("uri", uri) }))
    }

    suspend fun apps(): List<TvApp> =
        rpc("appControl", "getApplicationList").jsonArray[0].jsonArray.map {
            val o = it.jsonObject
            TvApp(o.str("title"), o.str("uri"), o["icon"]?.jsonPrimitive?.contentOrNull?.ifBlank { null })
        }

    suspend fun launchApp(uri: String) {
        rpc("appControl", "setActiveApp", params(buildJsonObject { put("uri", uri) }))
    }

    /** Types into whatever text field the TV currently shows (search boxes, passwords). */
    suspend fun typeText(text: String) {
        rpc("appControl", "setTextForm", params(JsonPrimitive(text)))
    }

    /** `null` when the TV is on its launcher or inside an app — Sony answers `7 Illegal State` there. */
    suspend fun nowPlaying(): NowPlaying? = try {
        val o = rpc("avContent", "getPlayingContentInfo").jsonArray[0].jsonObject
        NowPlaying(o.str("uri"), o.str("source"), o.str("title"))
    } catch (e: BraviaException.RpcError) {
        if (e.code == 7) null else throw e
    }

    // ── IRCC (remote keys) ──────────────────────────────────────────────────────────────────────────

    suspend fun sendKey(name: String) {
        val codes = remoteCodes()
        val resolved = codes.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: KEY_ALIASES[name.lowercase()]?.let { alias -> codes.keys.firstOrNull { it.equals(alias, ignoreCase = true) } }
            ?: throw BraviaException.UnknownKey(name)
        val code = codes.getValue(resolved)
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$code</IRCCCode></u:X_SendIRCC>""" +
            """</s:Body></s:Envelope>"""
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/sony/ircc").post(soap.toRequestBody(XML))
                .header("SOAPACTION", IRCC_SOAPACTION).also(::authHeaders).build()
            val (http, text, _) = execute(req)
            when {
                http == 403 || http == 401 -> throw BraviaException.AuthRequired(null)
                http != 200 -> {
                    val desc = text.substringAfter("<errorDescription>", "").substringBefore("<").ifBlank { "IRCC HTTP $http" }
                    val ecode = text.substringAfter("<errorCode>", "").substringBefore("<").toIntOrNull() ?: http
                    throw BraviaException.RpcError(ecode, desc)
                }
            }
        }
    }

    // ── pairing (accessControl.actRegister) ─────────────────────────────────────────────────────────

    /**
     * First half of PIN pairing: the TV shows a 4-digit code on screen and answers 401. If we are already
     * registered (cookie or PSK), the TV answers 200 and refreshes the cookie without any PIN.
     */
    suspend fun startPairing(clientId: String, nickname: String): PairingStart = withContext(Dispatchers.IO) {
        val (code, text, headers) = execute(registerRequest(clientId, nickname, pin = null))
        when (code) {
            401 -> PairingStart.PIN_SHOWN
            200 -> {
                parseRpc(code, text)          // surfaces "Illegal Argument" & co. loudly
                storeCookie(headers)
                PairingStart.ALREADY_PAIRED
            }
            else -> throw BraviaException.BadResponse("HTTP $code while starting pairing")
        }
    }

    /** Second half: submit the PIN the TV displayed. Stores and returns the auth cookie. */
    suspend fun completePairing(clientId: String, nickname: String, pin: String): BraviaCredentials.Cookie =
        withContext(Dispatchers.IO) {
            val (code, text, headers) = execute(registerRequest(clientId, nickname, pin))
            when (code) {
                401 -> throw BraviaException.WrongPin()
                200 -> {
                    parseRpc(code, text)
                    storeCookie(headers) ?: throw BraviaException.BadResponse("pairing succeeded but no auth cookie came back")
                }
                else -> throw BraviaException.BadResponse("HTTP $code while completing pairing")
            }
        }

    /** Refresh a registration the TV already knows (no PIN). Throws [BraviaException.AuthRequired] if it forgot us. */
    suspend fun renewPairing(clientId: String, nickname: String): BraviaCredentials.Cookie {
        if (startPairing(clientId, nickname) == PairingStart.PIN_SHOWN) throw BraviaException.AuthRequired(null)
        return credentials as? BraviaCredentials.Cookie
            ?: throw BraviaException.BadResponse("registration renewed without a cookie")
    }

    private fun registerRequest(clientId: String, nickname: String, pin: String?): Request {
        // NB: this firmware's actRegister wants "nickname" — "nick" (what most libraries send) is an Illegal Argument.
        val body = buildJsonObject {
            put("method", "actRegister")
            put("id", nextId.getAndIncrement())
            put("version", "1.0")
            put("params", buildJsonArray {
                add(buildJsonObject { put("clientid", clientId); put("nickname", nickname); put("level", "private") })
                add(buildJsonArray { add(buildJsonObject { put("value", "yes"); put("function", "WOL") }) })
            })
        }
        return Request.Builder().url("$base/sony/accessControl").post(body.toString().toRequestBody(JSON)).apply {
            if (pin != null) {
                header("Authorization", "Basic " + Base64.getEncoder().encodeToString(":$pin".toByteArray()))
            } else {
                authHeaders(this)
            }
        }.build()
    }

    private fun storeCookie(headers: Headers): BraviaCredentials.Cookie? {
        val setCookie = headers.values("Set-Cookie").firstOrNull { it.startsWith("auth=") } ?: return null
        val value = setCookie.substringAfter("auth=").substringBefore(';')
        val maxAge = Regex("(?i)Max-Age=(\\d+)").find(setCookie)?.groupValues?.get(1)?.toLongOrNull()
        val cookie = BraviaCredentials.Cookie(value, maxAge?.let { System.currentTimeMillis() + it * 1000 })
        credentials = cookie
        return cookie
    }
}
