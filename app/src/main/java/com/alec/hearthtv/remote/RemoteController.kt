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
import com.alec.hearthtv.protocol.bravia.TvInput
import com.alec.hearthtv.protocol.roku.RokuClient
import com.alec.hearthtv.protocol.roku.RokuException
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
    private val searchOpenDelayMs: Long = 1_500,
    /** Every failure lands here with a timestamp for the Diagnostics screen (SCOPE.md §4.2). */
    private val errors: ErrorLog = ErrorLog(),
    /** The Roku on one of the TV's HDMI ports, when the owner picked one in Setup (Stage 2b). */
    private val roku: RokuClient? = null,
    /** Route volume through the TV rather than straight to the Sonos (SCOPE.md D11). */
    volumeViaTv: Boolean = false,
) {
    companion object {
        const val WIFI_HINT = "Can't reach the TV. Is this phone on the home Wi-Fi, and is the TV plugged in?"
        const val SONOS_HINT = "Can't reach the Sonos. Is it powered and on the home Wi-Fi?"
        const val ROKU_HINT = "Can't reach the Roku. Is it plugged in and on the home Wi-Fi?"
        const val NO_TEXT_BOX = "Open a text box on the TV first — a search field or a sign-in box — then send again."
        const val NO_SEARCH_BOX = "The TV did not open a search box. Open one with the remote, then say it again."
    }

    private val _state = MutableStateFlow(RemoteUiState(volumeViaTv = volumeViaTv))
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
        val rokuState = readRoku()
        _state.update { s ->
            val lastKnown = (tvState as? TvState.On) ?: s.lastKnown
            s.copy(
                tv = tvState, pairing = pairing, sonos = sonosState, roku = rokuState, busy = false,
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
        val range = volume?.let { it.min..it.max } ?: 0..100
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
        return TvState.On(nowPlaying, volume?.level, volume?.muted ?: false, output, inputs, apps, range.first, range.last) to pairing
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

    private suspend fun readRoku(): RokuState {
        val box = roku ?: return RokuState.Absent
        return try {
            val info = box.deviceInfo()
            val active = box.activeApp()
            val apps = box.apps()
            RokuState.Ready(info.friendlyName, active.name.takeUnless { active.isHome }, active.isHome, apps)
        } catch (_: RokuException.LimitedMode) {
            if (_state.value.roku !is RokuState.Limited) errors.record("roku", "control refused: network access is Limited")
            RokuState.Limited(RokuException.LIMITED_HINT)
        } catch (e: RokuException) {
            if (_state.value.roku !is RokuState.Unreachable) errors.record("roku", "unreachable: ${e.cause?.message ?: e.message}")
            RokuState.Unreachable(ROKU_HINT)
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

    /** The volume bar: an absolute level on whichever device the buttons are driving (SCOPE.md §9.2). */
    suspend fun setVolumeLevel(level: Int) = action {
        val target = _state.value.volumeTarget
        val bounded = level.coerceIn(_state.value.volumeRange.first, _state.value.volumeRange.last)
        when (target) {
            VolumeTarget.SONOS -> {
                sonos!!.setVolume(bounded)
                updateSonos { it.copy(volume = bounded) }
            }
            VolumeTarget.TV -> {
                tv.setVolume(bounded)
                val v = runCatching { tv.volume() }.getOrNull()
                updateTv { it.copy(volume = v?.level ?: bounded, muted = v?.muted ?: it.muted) }
            }
        }
    }

    /**
     * Re-read only the volume, cheaply, so the bar follows changes made on the Sonos app or a physical remote
     * without waiting for the full refresh (SCOPE.md §9.3). Never raises busy and never surfaces an error.
     */
    suspend fun refreshVolume() {
        when (_state.value.volumeTarget) {
            VolumeTarget.SONOS -> {
                val s = sonos ?: return
                val level = runCatching { s.volume() }.getOrNull() ?: return
                val muted = runCatching { s.muted() }.getOrNull() ?: false
                updateSonos { it.copy(volume = level, muted = muted) }
            }
            VolumeTarget.TV -> {
                if (_state.value.tv !is TvState.On) return
                val v = runCatching { tv.volume() }.getOrNull() ?: return
                updateTv { it.copy(volume = v.level, muted = v.muted, volumeMin = v.min, volumeMax = v.max) }
            }
        }
    }

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
            _state.update { it.copy(lastError = NO_TEXT_BOX) }
            return@action
        }
        tv.typeText(text)
    }

    /**
     * Spoken search (SCOPE.md §9.4): type into a text box that is already open, or ask the TV to open one first
     * with its assistant key and type into that. If neither works, say so plainly rather than failing silently.
     * Whether this TV's assistant key yields a text field is the one part still to confirm on the real set.
     */
    suspend fun searchTv(query: String) = action {
        if (!tv.textInputActive()) {
            runCatching { tv.sendKey("Assists") }
            delay(searchOpenDelayMs)
            if (!tv.textInputActive()) {
                _state.update { it.copy(lastError = NO_SEARCH_BOX) }
                return@action
            }
        }
        tv.typeText(query)
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

    // ── Roku (Stage 2b) ─────────────────────────────────────────────────────────────────────────────

    /** The TV input the Roku sits on, found by its CEC name rather than a port number that may be mislabelled. */
    private fun rokuInput(on: TvState.On): TvInput? =
        on.inputs.firstOrNull { it.kind == InputKind.CEC_DEVICE && it.title.contains("roku", ignoreCase = true) }

    /** Put the Roku in front on the TV unless it already is (SCOPE.md §4.1: one tap also switches the TV). */
    private suspend fun bringRokuForward() {
        val on = _state.value.tv as? TvState.On ?: return
        val input = rokuInput(on) ?: return
        if (input.active || on.nowPlaying == input.displayName) return
        tv.switchInput(input.uri)
        lastLaunchedApp = null
        updateTv { it.copy(nowPlaying = input.displayName) }
    }

    suspend fun rokuKey(key: String) = action {
        val box = roku ?: return@action
        if (key == "Home") bringRokuForward()
        box.keypress(key)
        if (key == "Home") updateRoku { it.copy(activeApp = null, onHome = true) }
    }

    suspend fun rokuLaunch(appId: String) = action {
        val box = roku ?: return@action
        bringRokuForward()
        box.launch(appId)
        val name = (_state.value.roku as? RokuState.Ready)?.apps?.firstOrNull { it.id == appId }?.name
        updateRoku { it.copy(activeApp = name ?: it.activeApp, onHome = false) }
    }

    /** Switch the TV to the Roku and show its home screen. */
    suspend fun rokuHome() = rokuKey("Home")

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
            is VoiceCommand.SearchTv -> searchTv(cmd.query)
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
        } catch (e: RokuException.LimitedMode) {
            // The refresh already logged it if the box was locked before this press; keep one entry per cause.
            if (_state.value.roku !is RokuState.Limited) errors.record("roku", "control refused: network access is Limited")
            _state.update { it.copy(busy = false, roku = RokuState.Limited(RokuException.LIMITED_HINT), lastError = RokuException.LIMITED_HINT) }
        } catch (e: RokuException.Unreachable) {
            if (_state.value.roku !is RokuState.Unreachable) errors.record("roku", "unreachable: ${e.cause?.message ?: e.message}")
            _state.update { it.copy(busy = false, roku = RokuState.Unreachable(ROKU_HINT), lastError = ROKU_HINT) }
        } catch (e: RokuException) {
            errors.record("roku", e.message ?: "Roku error")
            _state.update { it.copy(busy = false, lastError = e.message) }
        }
    }

    private fun updateTv(f: (TvState.On) -> TvState.On) {
        _state.update { s -> (s.tv as? TvState.On)?.let { s.copy(tv = f(it)) } ?: s }
    }

    private fun updateRoku(f: (RokuState.Ready) -> RokuState.Ready) {
        _state.update { s -> (s.roku as? RokuState.Ready)?.let { s.copy(roku = f(it)) } ?: s }
    }

    private fun updateSonos(f: (SonosState.Ready) -> SonosState.Ready) {
        _state.update { s -> (s.sonos as? SonosState.Ready)?.let { s.copy(sonos = f(it)) } ?: s }
    }
}
