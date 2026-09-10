package com.alec.hearthtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alec.hearthtv.HearthGraph
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.remote.RemoteController
import com.alec.hearthtv.remote.RemoteUiState
import com.alec.hearthtv.remote.TvState
import com.alec.hearthtv.update.UpdateStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin Android wrapper: rebuilds the controller when settings change, serialises actions, coalesces held volume
 * presses, re-finds a TV that DHCP moved, and holds the update status.
 */
class RemoteViewModel : ViewModel() {
    private val _ui = MutableStateFlow(RemoteUiState())
    val ui: StateFlow<RemoteUiState> = _ui.asStateFlow()

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val _update = MutableStateFlow<UpdateStatus?>(null)
    val update: StateFlow<UpdateStatus?> = _update.asStateFlow()

    /** One-line confirmations for the snackbar (voice results, a re-found TV). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var controller: RemoteController? = null
    private var stateJob: Job? = null
    private val actions = Mutex()

    init {
        viewModelScope.launch {
            HearthGraph.settings.settings.collect { s ->
                _settings.value = s
                rebuild(s)
            }
        }
        viewModelScope.launch { _update.value = HearthGraph.updateChecker().check() }
    }

    private fun rebuild(s: AppSettings) {
        stateJob?.cancel()
        val c = HearthGraph.controller(s)
        controller = c
        if (c == null) {
            _ui.value = RemoteUiState()
            return
        }
        stateJob = viewModelScope.launch {
            c.state.collect { st ->
                _ui.value = st
                if (st.tv is TvState.Unreachable) maybeRelocate(s)
            }
        }
        refresh()
    }

    // ── a TV that moved (SCOPE.md §4.2) ─────────────────────────────────────────────────────────────

    private var relocating = false
    private var lastRelocateAt = 0L

    /** When the TV stops answering, look for it by MAC in case DHCP moved it — at most once a minute. */
    private fun maybeRelocate(s: AppSettings) {
        val mac = s.tvMac ?: return
        val now = System.currentTimeMillis()
        if (relocating || now - lastRelocateAt < 60_000) return
        relocating = true
        lastRelocateAt = now
        viewModelScope.launch {
            try {
                val host = runCatching { HearthGraph.relocator().find(mac, s.tvHost) }.getOrNull()
                if (host != null && host != s.tvHost) {
                    HearthGraph.settings.update { it.copy(tvHost = host) }   // the settings flow rebuilds the controller
                    toasts.emit("Found the TV at its new address ($host).")
                }
            } finally {
                relocating = false
            }
        }
    }

    // ── actions ─────────────────────────────────────────────────────────────────────────────────────

    private fun act(block: suspend RemoteController.() -> Unit) {
        val c = controller ?: return
        viewModelScope.launch { actions.withLock { c.block() } }
    }

    fun refresh() = act { refresh() }
    /** Background refresh: no progress bar, no error wipe. Used by the timer and on resume. */
    fun refreshQuiet() = act { refresh(quiet = true) }
    fun powerToggle() = act { powerToggle() }
    fun toggleMute() = act { toggleMute() }
    fun selectInput(uri: String) = act { selectInput(uri) }
    fun launchApp(uri: String) = act { launchApp(uri) }
    fun key(name: String) = act { key(name) }
    fun typeText(text: String) = act { typeText(text) }
    fun setOutput(output: SoundOutput) = act { setOutput(output) }
    fun fixSound() = act { fixSound() }
    fun setNightMode(on: Boolean) = act { setNightMode(on) }
    fun setSpeechEnhancement(on: Boolean) = act { setSpeechEnhancement(on) }
    fun startPairing() = act { startPairing() }
    fun completePairing(pin: String) = act { completePairing(pin) }
    fun recheckUpdate() = viewModelScope.launch { _update.value = HearthGraph.updateChecker().check() }

    // ── volume, debounced (SCOPE.md §4.2) ───────────────────────────────────────────────────────────

    private var pendingVolume = 0
    private var volumeJob: Job? = null

    /** Hold-to-repeat fires many steps quickly; fold them into as few device calls as the network keeps up with. */
    private fun volumeStep(delta: Int) {
        val c = controller ?: return
        pendingVolume += delta
        if (volumeJob?.isActive == true) return
        volumeJob = viewModelScope.launch {
            while (pendingVolume != 0) {
                val d = pendingVolume
                pendingVolume = 0
                actions.withLock { c.volumeStep(d) }
            }
        }
    }

    fun volumeUp() = volumeStep(+1)
    fun volumeDown() = volumeStep(-1)

    fun voice(heard: String) {
        val c = controller ?: return
        viewModelScope.launch { actions.withLock { toasts.emit(c.voice(heard)) } }
    }
}
