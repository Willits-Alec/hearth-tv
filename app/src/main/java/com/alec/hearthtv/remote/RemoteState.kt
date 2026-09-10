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
) {
    val volumeTarget: VolumeTarget
        get() = if ((tv as? TvState.On)?.output == SoundOutput.AUDIO_SYSTEM && sonos is SonosState.Ready) VolumeTarget.SONOS
        else VolumeTarget.TV
}
