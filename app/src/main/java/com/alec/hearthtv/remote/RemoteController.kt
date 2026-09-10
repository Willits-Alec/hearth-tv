package com.alec.hearthtv.remote

import com.alec.hearthtv.diagnostics.ErrorLog
import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaCredentials
import com.alec.hearthtv.protocol.bravia.BraviaException
import com.alec.hearthtv.protocol.bravia.InputKind
import com.alec.hearthtv.protocol.bravia.PairingStart
import com.alec.hearthtv.protocol.bravia.PowerState
import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.protocol.bravia.TvApp
import com.alec.hearthtv.protocol.sonos.SonosClient
import com.alec.hearthtv.protocol.sonos.SonosException
import com.alec.hearthtv.voice.VoiceCommand
import com.alec.hearthtv.voice.VoiceCommandParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The brain behind the Remote screen, pure Kotlin so it runs under JVM tests against the fakes.
 * Owns [state], routes every button to the right device, and turns protocol failures into things a person can
 * read. Pinned by RemoteControllerTest.
 */
class RemoteController(
    private val tv: BraviaClient,
    private val sonos: SonosClient?,
    private val credentials: CredentialStore,
    private val tvMac: String?,
    private val wakeOnLan: suspend (mac: String) -> Unit,
    private val clientId: String = "HearthTV:phone",
    private val nickname: String = "Hearth TV",
    private val pollDelayMs: Long = 1000,
    private val powerOnTimeoutMs: Long = 15_000,
    /** Every failure lands here with a timestamp for the Diagnostics screen (SCOPE.md §4.2). */
    private val errors: ErrorLog = ErrorLog(),
) {
    companion object {
        const val WIFI_HINT = "Can't reach the TV. Is this phone on the home Wi-Fi, and is the TV plugged in?"
        const val SONOS_HINT = "Can't reach the Sonos. Is it powered and on the home Wi-Fi?"
    }

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    /** Sony reports nothing while an app is in front, so remember what we launched until an input takes over. */
    private var lastLaunchedApp: String? = null

    // ── reading ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Re-read everything. A quiet refresh (background timer, resume) never raises [RemoteUiState.busy] and never
     * clears an error the person has not seen yet; a loud one (the refresh button) does both.
     */
    suspend fun refresh(quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(busy = true, lastError = null) }
        val (tvState, pairing) = readTv()
        val sonosState = readSonos()
        _state.update { s ->
            val lastKnown = (tvState as? TvState.On) ?: s.lastKnown
            s.copy(
                tv = tvState, pairing = pairing, sonos = sonosState, busy = false,
                lastKnown = lastKnown,
                reconnecting = tvState is TvState.Unreachable && lastKnown != null,
            )
        }
    }

    private suspend fun readTv(): Pair<TvState, PairingState> {
        val power = try {
            tv.powerStatus()
        } catch (e: BraviaException.Unreachable) {
            noteTvGone("unreachable: ${e.cause?.message ?: e.message}")
            return TvState.Unreachable(WIFI_HINT) to _state.value.pairing
        } catch (e: BraviaException) {
            noteTvGone(e.message ?: "TV error")
            return TvState.Unreachable(e.message ?: "TV error") to _state.value.pairing
        }
        val assumedPairing = if (tv.credentials == BraviaCredentials.None) PairingState.NeedsPairing(false) else PairingState.Paired
        if (power == PowerState.STANDBY) return TvState.Standby to assumedPairing

        val volume = runCatching { tv.volume() }.getOrNull()          // 40005 while switching off → null
        val output = runCatching { tv.soundOutput() }.getOrNull()
        val inputs = runCatching { tv.inputs() }.getOrDefault(emptyList())

        var pairing: PairingState = PairingState.Paired
        var apps: List<TvApp> = emptyList()
        var nowPlaying: String? = null
        try {
            apps = tv.apps()
            nowPlaying = tv.nowPlaying()?.title ?: lastLaunchedApp
        } catch (_: BraviaException.AuthRequired) {
            pairing = PairingState.NeedsPairing(pinShown = false)
        } catch (e: BraviaException) {
            errors.record("tv", e.message ?: "TV error")
            _state.update { it.copy(lastError = e.message) }
        }
        return TvState.On(nowPlaying, volume?.level, volume?.muted ?: false, output, inputs, apps) to pairing
    }

    private suspend fun readSonos(): SonosState {
        val s = sonos ?: return SonosState.Absent
        return try {
            SonosState.Ready(
                volume = s.volume(),
                muted = s.muted(),
                nightMode = s.nightMode(),
                speechEnhancement = s.speechEnhancement(),
                source = s.source().kind,
            )
        } catch (e: SonosException) {
            if (_state.value.sonos !is SonosState.Unreachable) errors.record("sonos", "unreachable: ${e.message}")
            SonosState.Unreachable(SONOS_HINT)
        }
    }

    /** Log the moment the TV drops out, not every quiet refresh while it stays out. */
    private fun noteTvGone(detail: String) {
        if (_state.value.tv !is TvState.Unreachable) errors.record("tv", detail)
    }

    // ── power ───────────────────────────────────────────────────────────────────────────────────────

    suspend fun powerToggle() = action {
        if (_state.value.tv is TvState.On) {
            tv.setPower(false)
            _state.update { it.copy(tv = TvState.Standby) }
        } else {
            tvMac?.let { mac -> runCatching { wakeOnLan(mac) } }
            runCatching { tv.setPower(true) }                          // works from networked standby
            val attempts = (powerOnTimeoutMs / pollDelayMs).toInt().coerceAtLeast(1)
            repeat(attempts) {
                if (runCatching { tv.powerStatus() }.getOrNull() == PowerState.ACTIVE) return@repeat
                delay(pollDelayMs)
            }
            refresh()
        }
    }

    // ── volume (D4 routing) ─────────────────────────────────────────────────────────────────────────

    suspend fun volumeUp() = volumeStep(+1)
    suspend fun volumeDown() = volumeStep(-1)

    suspend fun volumeStep(delta: Int) = action {
        when (_state.value.volumeTarget) {
            VolumeTarget.SONOS -> {
                val level = sonos!!.volumeStep(delta)
                updateSonos { it.copy(volume = level) }
            }
            VolumeTarget.TV -> {
                tv.volumeStep(delta)
                val v = tv.volume()
                updateTv { it.copy(volume = v.level, muted = v.muted) }
            }
        }
    }

    suspend fun toggleMute() = action {
        when (_state.value.volumeTarget) {
            VolumeTarget.SONOS -> {
                val ready = _state.value.sonos as? SonosState.Ready
                val mute = !(ready?.muted ?: sonos!!.muted())
                sonos!!.setMute(mute)
                updateSonos { it.copy(muted = mute) }
            }
            VolumeTarget.TV -> {
                val on = _state.value.tv as? TvState.On
                val mute = !(on?.muted ?: false)
                tv.setMute(mute)
                updateTv { it.copy(muted = mute) }
            }
        }
    }

    // ── TV actions ──────────────────────────────────────────────────────────────────────────────────

    suspend fun selectInput(uri: String) = action {
        tv.switchInput(uri)
        lastLaunchedApp = null
        val title = tv.nowPlaying()?.title
        updateTv { it.copy(nowPlaying = title) }
    }

    suspend fun launchApp(uri: String) = action {
        tv.launchApp(uri)
        lastLaunchedApp = (_state.value.tv as? TvState.On)?.apps?.firstOrNull { it.uri == uri }?.title ?: uri
        val title = tv.nowPlaying()?.title ?: lastLaunchedApp
        updateTv { it.copy(nowPlaying = title) }
    }

    suspend fun key(name: String) = action { tv.sendKey(name) }

    suspend fun typeText(text: String) = action {
        if (!tv.textInputActive()) {
            _state.update { it.copy(lastError = "Open a text box on the TV first — a search field or a sign-in box — then send again.") }
            return@action
        }
        tv.typeText(text)
    }

    suspend fun setOutput(output: SoundOutput) = action {
        tv.setSoundOutput(output)
        val current = runCatching { tv.soundOutput() }.getOrDefault(output)
        updateTv { it.copy(output = current) }
    }

    /** One tap: TV → Audio system, Arc → its TV input and playing, then re-read everything. */
    suspend fun fixSound() = action {
        tv.setSoundOutput(SoundOutput.AUDIO_SYSTEM)
        sonos?.switchToTv()
        refresh()
    }

    // ── Sonos extras ────────────────────────────────────────────────────────────────────────────────

    suspend fun setNightMode(on: Boolean) = action {
        sonos?.setNightMode(on)
        updateSonos { it.copy(nightMode = on) }
    }

    suspend fun setSpeechEnhancement(on: Boolean) = action {
        sonos?.setSpeechEnhancement(on)
        updateSonos { it.copy(speechEnhancement = on) }
    }

    // ── voice ───────────────────────────────────────────────────────────────────────────────────────

    /** Runs one spoken instruction and returns the words to show the person. */
    suspend fun voice(heard: String): String {
        val on = (_state.value.tv as? TvState.On) ?: _state.value.lastKnown
        val cmd = VoiceCommandParser(
            apps = on?.apps?.map { it.title } ?: emptyList(),
            // CEC devices first so a spoken name lands on the device, not on a stale port label
            inputs = on?.inputs?.sortedBy { if (it.kind == InputKind.CEC_DEVICE) 0 else 1 }?.map { it.displayName } ?: emptyList(),
        ).parse(heard)
        when (cmd) {
            is VoiceCommand.Volume -> volumeStep(cmd.steps)
            is VoiceCommand.Mute -> setMuteRouted(cmd.on)
            is VoiceCommand.Power -> if (cmd.on != (_state.value.tv is TvState.On)) powerToggle()
            is VoiceCommand.LaunchApp -> on?.apps?.firstOrNull { it.title == cmd.app }?.let { launchApp(it.uri) }
            is VoiceCommand.SelectInput -> on?.inputs?.firstOrNull { it.displayName == cmd.input }?.let { selectInput(it.uri) }
            is VoiceCommand.Key -> key(cmd.key)
            VoiceCommand.FixSound -> fixSound()
            is VoiceCommand.NightMode -> setNightMode(cmd.on)
            is VoiceCommand.SpeechEnhancement -> setSpeechEnhancement(cmd.on)
            is VoiceCommand.TypeText -> typeText(cmd.text)
            is VoiceCommand.Unknown -> _state.update { it.copy(lastError = cmd.describe()) }
        }
        return cmd.describe()
    }

    private suspend fun setMuteRouted(mute: Boolean) = action {
        when (_state.value.volumeTarget) {
            VolumeTarget.SONOS -> { sonos!!.setMute(mute); updateSonos { it.copy(muted = mute) } }
            VolumeTarget.TV -> { tv.setMute(mute); updateTv { it.copy(muted = mute) } }
        }
    }

    // ── pairing ─────────────────────────────────────────────────────────────────────────────────────

    suspend fun startPairing(): PairingState {
        val result = try {
            when (tv.startPairing(clientId, nickname)) {
                PairingStart.PIN_SHOWN -> PairingState.NeedsPairing(pinShown = true)
                PairingStart.ALREADY_PAIRED -> { credentials.save(tv.credentials); PairingState.Paired }
            }
        } catch (e: BraviaException) {
            PairingState.Failed(e.message ?: "pairing failed")
        }
        _state.update { it.copy(pairing = result) }
        return result
    }

    suspend fun completePairing(pin: String): PairingState {
        val result = try {
            val cookie = tv.completePairing(clientId, nickname, pin.trim())
            credentials.save(cookie)
            PairingState.Paired
        } catch (_: BraviaException.WrongPin) {
            PairingState.Failed("The TV rejected that code. Read the 4 digits on the screen and try again.")
        } catch (e: BraviaException) {
            PairingState.Failed(e.message ?: "pairing failed")
        }
        if (result is PairingState.Failed) errors.record("pairing", result.reason)
        _state.update { it.copy(pairing = result) }
        return result
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun action(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
            _state.update { it.copy(busy = false) }
        } catch (e: BraviaException.AuthRequired) {
            errors.record("tv", "needs pairing")
            _state.update { it.copy(busy = false, pairing = PairingState.NeedsPairing(false), lastError = "The TV needs pairing before it accepts that.") }
        } catch (e: BraviaException.Unreachable) {
            noteTvGone("unreachable: ${e.cause?.message ?: e.message}")
            _state.update { it.copy(busy = false, tv = TvState.Unreachable(WIFI_HINT), lastError = WIFI_HINT) }
        } catch (e: BraviaException) {
            errors.record("tv", e.message ?: "TV error")
            _state.update { it.copy(busy = false, lastError = e.message) }
        } catch (e: SonosException) {
            errors.record("sonos", e.message ?: "Sonos error")
            _state.update { it.copy(busy = false, lastError = e.message) }
        }
    }

    private fun updateTv(f: (TvState.On) -> TvState.On) {
        _state.update { s -> (s.tv as? TvState.On)?.let { s.copy(tv = f(it)) } ?: s }
    }

    private fun updateSonos(f: (SonosState.Ready) -> SonosState.Ready) {
        _state.update { s -> (s.sonos as? SonosState.Ready)?.let { s.copy(sonos = f(it)) } ?: s }
    }
}
