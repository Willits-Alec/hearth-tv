package com.alec.hearthtv.protocol.sonos

import java.io.IOException

data class SonosDevice(
    val modelName: String,
    val roomName: String,
    val softwareVersion: String,
    /** The player id, e.g. `RINCON_...01400`, without the `uuid:` prefix. */
    val uuid: String,
    val serialNum: String,
    val zoneType: Int?,
) {
    /** A TV-connected bar: the only kind of player whose "switch to TV" makes sense. */
    val isHomeTheatre: Boolean
        get() = Regex("Arc|Beam|Ray|Playbar|Playbase", RegexOption.IGNORE_CASE).containsMatchIn(modelName)
}

enum class TransportState { PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN }

enum class SourceKind { TV, QUEUE, LINE_IN, STREAM, OTHER, NONE }

data class SonosSource(val kind: SourceKind, val uri: String)

/** One `<ZoneGroup>` of `GetZoneGroupState`: a room, or a stereo pair, or a home-theatre set with bonded satellites. */
data class ZoneGroup(
    val coordinatorUuid: String,
    val name: String,
    val coordinatorHost: String,
    val memberUuids: List<String>,
    val satelliteUuids: List<String>,
)

sealed class SonosException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: IOException) : SonosException("Sonos unreachable: ${cause.message}", cause)
    class UpnpError(val code: Int, val description: String) : SonosException("UPnP error $code: $description")
    class BadResponse(detail: String) : SonosException("unexpected reply from the Sonos: $detail")
}
