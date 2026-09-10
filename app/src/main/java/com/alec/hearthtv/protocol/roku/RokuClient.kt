package com.alec.hearthtv.protocol.roku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Roku External Control Protocol on :8060 — the slice a remote needs: device info, the active app, the app list,
 * key presses and app launch. Plain HTTP with small XML replies, parsed with the same light touch as the Sonos
 * client. Pinned by RokuClientTest against FakeRoku, which replays the Ultra's captured replies.
 */
class RokuClient(baseUrl: String, private val http: OkHttpClient) {
    private val base = baseUrl.trimEnd('/')

    companion object {
        /** The ECP key names this remote sends, as Roku documents them. */
        val KEYS = listOf("Home", "Back", "Up", "Down", "Left", "Right", "Select", "Play", "Rev", "Fwd", "InstantReplay", "Info", "Search")
        private const val LIMITED_TEXT = "not allowed in Limited mode"
        private val APP = Regex("<app\\s+([^>]*)>([^<]*)</app>")
    }

    suspend fun deviceInfo(): RokuDeviceInfo {
        val xml = get("/query/device-info")
        return RokuDeviceInfo(
            modelName = tag(xml, "model-name"),
            modelNumber = tag(xml, "model-number"),
            friendlyName = tag(xml, "user-device-name").ifBlank { tag(xml, "friendly-device-name") }.ifBlank { tag(xml, "model-name") },
            softwareVersion = tag(xml, "software-version"),
            powerMode = tag(xml, "power-mode"),
            ecpSettingMode = tag(xml, "ecp-setting-mode"),
        )
    }

    suspend fun activeApp(): RokuActiveApp {
        val xml = get("/query/active-app")
        val m = APP.find(xml) ?: throw RokuException.BadResponse(200, "no <app> in active-app")
        val a = attrs(m.groupValues[1])
        return RokuActiveApp(a["id"] ?: "", unescape(m.groupValues[2]), a["type"] ?: "")
    }

    /** Every installed channel, in the box's own order. Throws [RokuException.LimitedMode] when control is off. */
    suspend fun apps(): List<RokuApp> {
        val xml = get("/query/apps")
        return APP.findAll(xml).map { m ->
            val a = attrs(m.groupValues[1])
            RokuApp(a["id"] ?: "", unescape(m.groupValues[2]), a["type"] ?: "", a["version"] ?: "")
        }.toList()
    }

    suspend fun keypress(key: String) = post("/keypress/$key")

    suspend fun launch(appId: String) = post("/launch/$appId")

    // ── transport ───────────────────────────────────────────────────────────────────────────────────

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val (code, text) = execute(Request.Builder().url("$base$path").get().build())
        expectOk(code, text, path)
        text
    }

    private suspend fun post(path: String): Unit = withContext(Dispatchers.IO) {
        val (code, text) = execute(Request.Builder().url("$base$path").post(ByteArray(0).toRequestBody()).build())
        expectOk(code, text, path)
    }

    private fun expectOk(code: Int, text: String, path: String) {
        if (code == 200) return
        if (code == 403 && text.contains(LIMITED_TEXT, ignoreCase = true)) throw RokuException.LimitedMode()
        throw RokuException.BadResponse(code, text.trim().ifBlank { path })
    }

    private fun execute(req: Request): Pair<Int, String> = try {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: IOException) {
        throw RokuException.Unreachable(e)
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────────

    private fun tag(xml: String, name: String): String =
        Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.let { unescape(it) } ?: ""

    private fun attrs(fragment: String): Map<String, String> =
        Regex("([\\w-]+)=\"([^\"]*)\"").findAll(fragment).associate { it.groupValues[1] to it.groupValues[2] }

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}
