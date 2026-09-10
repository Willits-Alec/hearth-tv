package com.alec.hearthtv.protocol.bravia

import java.io.IOException

/** How the client proves itself to the TV. The PIN flow yields a [Cookie]; a pre-shared key is the fallback. */
sealed class BraviaCredentials {
    data object None : BraviaCredentials()
    data class Cookie(val value: String, val expiresAtEpochMs: Long?) : BraviaCredentials()
    data class Psk(val key: String) : BraviaCredentials()
}

enum class PowerState { ACTIVE, STANDBY }

enum class PairingStart { PIN_SHOWN, ALREADY_PAIRED }

data class InterfaceInfo(
    val modelName: String,
    val productName: String,
    val productCategory: String,
    val interfaceVersion: String,
)

data class SystemInfo(
    val model: String,
    val serial: String,
    val macAddr: String,
    val name: String,
    val generation: String,
    val cid: String,
    val area: String,
    val region: String,
)

data class VolumeInfo(val target: String, val level: Int, val muted: Boolean, val min: Int, val max: Int)

/** Sony's `outputTerminal` values. */
enum class SoundOutput(val wire: String) {
    AUDIO_SYSTEM("audioSystem"),
    TV_SPEAKER("speaker"),
    HDMI("hdmi"),
    TV_SPEAKER_AND_HDMI("speaker_hdmi");

    companion object {
        fun fromWire(wire: String): SoundOutput =
            entries.firstOrNull { it.wire == wire } ?: throw BraviaException.BadResponse("unknown outputTerminal '$wire'")
    }
}

enum class InputKind { HDMI, CEC_DEVICE, COMPOSITE, OTHER }

/**
 * One entry of `getCurrentExternalInputsStatus`. While the TV is on, connected HDMI-CEC devices appear as their
 * own entries (`extInput:cec?...`, titled by the device: "Roku Ultra", "Sonos Arc") alongside the raw HDMI ports.
 * The owner's port labels can be stale ("Roku" sat on HDMI 4 while the Roku was on HDMI 2), so the UI shows CEC
 * device names first and treats labels as hints.
 */
data class TvInput(
    val uri: String,
    val title: String,
    val label: String,
    val connected: Boolean,
    val active: Boolean,
    val icon: String?,
) {
    val kind: InputKind
        get() = when {
            uri.startsWith("extInput:cec") -> InputKind.CEC_DEVICE
            uri.startsWith("extInput:hdmi") -> InputKind.HDMI
            uri.startsWith("extInput:composite") -> InputKind.COMPOSITE
            else -> InputKind.OTHER
        }

    /** The owner's own label ("Game") when set, else the TV's name for the port or the CEC device's name. */
    val displayName: String get() = label.ifBlank { title }

    /** The HDMI port number when the URI carries one (`port=N`), for both HDMI and CEC entries. */
    val port: Int? get() = Regex("port=(\\d+)").find(uri)?.groupValues?.get(1)?.toIntOrNull()
}

data class TvApp(val title: String, val uri: String, val icon: String?)

data class NowPlaying(val uri: String, val source: String, val title: String)

sealed class BraviaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The TV wants pairing (or a PSK) before it will answer this. `authUrl` is the TV's own pairing page. */
    class AuthRequired(val authUrl: String?) : BraviaException("the TV requires pairing")
    class WrongPin : BraviaException("the TV rejected the PIN")
    /** A Sony JSON-RPC error, e.g. 7 "Illegal State", 12 "No Such Method", 40005 "Display Is Turned off". */
    class RpcError(val code: Int, val reason: String) : BraviaException("Sony error $code: $reason")
    class UnknownKey(val key: String) : BraviaException("no remote key named '$key'")
    class Unreachable(cause: IOException) : BraviaException("TV unreachable: ${cause.message}", cause)
    class BadResponse(detail: String) : BraviaException("unexpected reply from the TV: $detail")
}
