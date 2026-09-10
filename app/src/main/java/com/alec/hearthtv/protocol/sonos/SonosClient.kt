package com.alec.hearthtv.protocol.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * The slice of Sonos's local UPnP API a home-theatre remote needs, spoken to the group coordinator (the bar).
 * Bonded satellites — the Sub and the surrounds — are invisible members, so the coordinator's RenderingControl
 * is the whole system's volume. Pinned by SonosClientTest against FakeSonos, which replays the Arc's replies.
 */
class SonosClient(baseUrl: String, private val http: OkHttpClient) {
    private val base = baseUrl.trimEnd('/')
    @Volatile private var cachedUuid: String? = null

    companion object {
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()
        private const val RC = "MediaRenderer/RenderingControl"
        private const val AV = "MediaRenderer/AVTransport"
        private const val ZGT = "ZoneGroupTopology"

        fun tvInputUri(uuid: String) = "x-sonos-htastream:$uuid:spdif"

        fun kindOf(uri: String): SourceKind = when {
            uri.isBlank() -> SourceKind.NONE
            uri.startsWith("x-sonos-htastream:") -> SourceKind.TV
            uri.startsWith("x-rincon-queue:") -> SourceKind.QUEUE
            uri.startsWith("x-rincon-stream:") -> SourceKind.LINE_IN
            uri.startsWith("x-sonosapi-radio:") || uri.startsWith("x-sonosapi-stream:") ||
                uri.startsWith("x-rincon-mp3radio:") || uri.startsWith("aac:") || uri.startsWith("hls-radio:") -> SourceKind.STREAM
            else -> SourceKind.OTHER
        }
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────────

    /** One SOAP action against `/<path>/Control`; returns the response envelope or throws [SonosException]. */
    suspend fun soap(path: String, service: String, action: String, args: String): String = withContext(Dispatchers.IO) {
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$args</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder().url("$base/$path/Control").post(body.toRequestBody(XML))
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"").build()
        val (code, text) = execute(req)
        if (code != 200) {
            val ec = tag(text, "errorCode").toIntOrNull() ?: code
            val desc = tag(text, "errorDescription").ifBlank { "HTTP $code" }
            throw SonosException.UpnpError(ec, desc)
        }
        text
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val (code, text) = execute(Request.Builder().url("$base$path").get().build())
        if (code != 200) throw SonosException.BadResponse("HTTP $code for $path")
        text
    }

    private fun execute(req: Request): Pair<Int, String> = try {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: IOException) {
        throw SonosException.Unreachable(e)
    }

    private suspend fun rc(action: String, args: String) = soap(RC, "RenderingControl", action, "<InstanceID>0</InstanceID>$args")
    private suspend fun av(action: String, args: String = "") = soap(AV, "AVTransport", action, "<InstanceID>0</InstanceID>$args")

    // ── device ──────────────────────────────────────────────────────────────────────────────────────

    suspend fun description(): SonosDevice {
        val xml = get("/xml/device_description.xml")
        val d = SonosDevice(
            modelName = unescape(tag(xml, "modelName")),
            roomName = unescape(tag(xml, "roomName")),
            softwareVersion = tag(xml, "softwareVersion"),
            uuid = tag(xml, "UDN").removePrefix("uuid:"),
            serialNum = tag(xml, "serialNum"),
            zoneType = tag(xml, "zoneType").toIntOrNull(),
        )
        if (d.uuid.isBlank()) throw SonosException.BadResponse("device description without a UDN")
        cachedUuid = d.uuid
        return d
    }

    private suspend fun uuid(): String = cachedUuid ?: description().uuid

    // ── volume / mute / EQ ──────────────────────────────────────────────────────────────────────────

    suspend fun volume(): Int = tag(rc("GetVolume", "<Channel>Master</Channel>"), "CurrentVolume").toIntOrNull()
        ?: throw SonosException.BadResponse("no CurrentVolume")

    suspend fun setVolume(level: Int) {
        rc("SetVolume", "<Channel>Master</Channel><DesiredVolume>${level.coerceIn(0, 100)}</DesiredVolume>")
    }

    /** Relative change; returns the player's new level. */
    suspend fun volumeStep(delta: Int): Int =
        tag(rc("SetRelativeVolume", "<Channel>Master</Channel><Adjustment>$delta</Adjustment>"), "NewVolume").toIntOrNull()
            ?: throw SonosException.BadResponse("no NewVolume")

    suspend fun muted(): Boolean = tag(rc("GetMute", "<Channel>Master</Channel>"), "CurrentMute") == "1"

    suspend fun setMute(mute: Boolean) {
        rc("SetMute", "<Channel>Master</Channel><DesiredMute>${if (mute) 1 else 0}</DesiredMute>")
    }

    suspend fun nightMode(): Boolean = eq("NightMode")
    suspend fun setNightMode(on: Boolean) = setEq("NightMode", on)
    suspend fun speechEnhancement(): Boolean = eq("DialogLevel")
    suspend fun setSpeechEnhancement(on: Boolean) = setEq("DialogLevel", on)

    private suspend fun eq(type: String): Boolean = tag(rc("GetEQ", "<EQType>$type</EQType>"), "CurrentValue") == "1"
    private suspend fun setEq(type: String, on: Boolean) {
        rc("SetEQ", "<EQType>$type</EQType><DesiredValue>${if (on) 1 else 0}</DesiredValue>")
    }

    // ── transport / source ──────────────────────────────────────────────────────────────────────────

    suspend fun transportState(): TransportState = when (tag(av("GetTransportInfo"), "CurrentTransportState")) {
        "PLAYING" -> TransportState.PLAYING
        "PAUSED_PLAYBACK" -> TransportState.PAUSED
        "STOPPED" -> TransportState.STOPPED
        "TRANSITIONING" -> TransportState.TRANSITIONING
        else -> TransportState.UNKNOWN
    }

    suspend fun play() { av("Play", "<Speed>1</Speed>") }
    suspend fun pause() { av("Pause") }

    suspend fun source(): SonosSource {
        val uri = unescape(tag(av("GetMediaInfo"), "CurrentURI"))
        return SonosSource(kindOf(uri), uri)
    }

    /** Puts the bar back on its TV input (the optical/eARC stream) and makes sure it is playing. */
    suspend fun switchToTv() {
        av("SetAVTransportURI", "<CurrentURI>${tvInputUri(uuid())}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        play()
    }

    // ── topology ────────────────────────────────────────────────────────────────────────────────────

    suspend fun zoneGroups(): List<ZoneGroup> {
        val envelope = soap(ZGT, "ZoneGroupTopology", "GetZoneGroupState", "")
        val inner = unescape(tag(envelope, "ZoneGroupState"))
        return parseZoneGroups(inner)
    }

    /** The group this player coordinates — for a bar, its home-theatre set. */
    suspend fun homeTheatreGroup(): ZoneGroup? {
        val me = uuid()
        val groups = zoneGroups()
        return groups.firstOrNull { it.coordinatorUuid == me } ?: groups.firstOrNull { it.satelliteUuids.isNotEmpty() }
    }

    internal fun parseZoneGroups(xml: String): List<ZoneGroup> {
        val groups = mutableListOf<ZoneGroup>()
        val blocks = xml.split("<ZoneGroup ").drop(1)
        for (block in blocks) {
            val coordinator = attr(block.substringBefore(">"), "Coordinator") ?: continue
            val body = block.substringBefore("</ZoneGroup>")
            val members = Regex("<ZoneGroupMember\\s([^>]*?)/?>").findAll(body).map { attrs(it.groupValues[1]) }.toList()
            val satellites = Regex("<Satellite\\s([^>]*?)/?>").findAll(body).map { attrs(it.groupValues[1]) }.toList()
            val coordMember = members.firstOrNull { it["UUID"] == coordinator } ?: members.firstOrNull()
            val name = coordMember?.get("ZoneName") ?: ""
            val host = coordMember?.get("Location")?.let { runCatching { URI(it).host }.getOrNull() } ?: ""
            groups += ZoneGroup(
                coordinatorUuid = coordinator,
                name = name,
                coordinatorHost = host,
                memberUuids = members.mapNotNull { it["UUID"] },
                satelliteUuids = satellites.mapNotNull { it["UUID"] },
            )
        }
        return groups
    }

    // ── xml helpers (a fake-proof regex subset; the replies are small and flat) ─────────────────────

    private fun tag(xml: String, name: String): String {
        val open = xml.indexOf("<$name>")
        if (open < 0) return ""
        val start = open + name.length + 2
        val end = xml.indexOf("</$name>", start)
        return if (end < 0) "" else xml.substring(start, end).trim()
    }

    private fun attr(fragment: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"").find(fragment)?.groupValues?.get(1)

    private fun attrs(fragment: String): Map<String, String> =
        Regex("(\\w+)=\"([^\"]*)\"").findAll(fragment).associate { it.groupValues[1] to it.groupValues[2] }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
