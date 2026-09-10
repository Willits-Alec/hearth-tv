package com.alec.hearthtv.remote

import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.protocol.bravia.TvApp
import com.alec.hearthtv.protocol.bravia.TvInput
import com.alec.hearthtv.protocol.roku.RokuApp
import com.alec.hearthtv.protocol.sonos.SourceKind

sealed class TvState {
    data object Unknown : TvState()
    data class Unreachable(val hint: String) : TvState()
    data object Standby : TvState()
    data class On(
        /** What the TV says is in front: a CEC device name, an HDMI port, or the app we last launched. */
        val nowPlaying: String?,
        val volume: Int?,
        val muted: Boolean,
        val output: SoundOutput?,
        val inputs: List<TvInput>,
        val apps: List<TvApp>,
        /** The TV's own scale, which is not always 0 to 100. */
        val volumeMin: Int = 0,
        val volumeMax: Int = 100,
    ) : TvState()
}

sealed class SonosState {
    data object Absent : SonosState()
    data class Unreachable(val hint: String) : SonosState()
    data class Ready(
        val volume: Int,
        val muted: Boolean,
        val nightMode: Boolean,
        val speechEnhancement: Boolean,
        val source: SourceKind,
    ) : SonosState()
}

sealed class RokuState {
    data object Absent : RokuState()
    data class Unreachable(val hint: String) : RokuState()
    /** The box refuses control until its network access is set to Default; [hint] says how (SCOPE.md §3). */
    data class Limited(val hint: String) : RokuState()
    data class Ready(val name: String, val activeApp: String?, val onHome: Boolean, val apps: List<RokuApp>) : RokuState()
}

sealed class PairingState {
    data object Unknown : PairingState()
    data object Paired : PairingState()
    data class NeedsPairing(val pinShown: Boolean) : PairingState()
    data class Failed(val reason: String) : PairingState()
}

/** Where the volume buttons go right now (SCOPE.md D4). */
enum class VolumeTarget { SONOS, TV }

data class RemoteUiState(
    val tv: TvState = TvState.Unknown,
    val sonos: SonosState = SonosState.Absent,
    val roku: RokuState = RokuState.Absent,
    val pairing: PairingState = PairingState.Unknown,
    val busy: Boolean = false,
    val lastError: String? = null,
    /** The last good TV read, so the controls stay on screen while the TV is briefly unreachable. */
    val lastKnown: TvState.On? = null,
    /** True while the TV cannot be reached but we have a last known screen to keep showing. */
    val reconnecting: Boolean = false,
    /**
     * The owner asked for volume to go through the TV so its own on-screen bar appears (SCOPE.md §9.3, D11).
     * Off by default: the TV relays to the Arc over CEC in steps of two, which is coarser than talking to the
     * Arc directly.
     */
    val volumeViaTv: Boolean = false,
) {
    val volumeTarget: VolumeTarget
        get() = if (!volumeViaTv && (tv as? TvState.On)?.output == SoundOutput.AUDIO_SYSTEM && sonos is SonosState.Ready) VolumeTarget.SONOS
        else VolumeTarget.TV

    /** The scale the volume bar should span for whichever device the buttons are driving. */
    val volumeRange: IntRange
        get() = when (volumeTarget) {
            VolumeTarget.SONOS -> 0..100
            VolumeTarget.TV -> (tv as? TvState.On ?: lastKnown)?.let { it.volumeMin..it.volumeMax } ?: 0..100
        }

    /** The level to show, or null while it is unknown. */
    val volumeLevel: Int?
        get() = when (volumeTarget) {
            VolumeTarget.SONOS -> (sonos as? SonosState.Ready)?.volume
            VolumeTarget.TV -> (tv as? TvState.On ?: lastKnown)?.volume
        }

    val volumeMuted: Boolean
        get() = when (volumeTarget) {
            VolumeTarget.SONOS -> (sonos as? SonosState.Ready)?.muted ?: false
            VolumeTarget.TV -> (tv as? TvState.On ?: lastKnown)?.muted ?: false
        }
}
