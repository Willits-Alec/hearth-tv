package com.alec.hearthtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alec.hearthtv.HearthGraph
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.remote.RemoteController
import com.alec.hearthtv.remote.RemoteUiState
import com.alec.hearthtv.update.UpdateStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thin Android wrapper: rebuilds the controller when settings change, serialises actions, holds the update status. */
class RemoteViewModel : ViewModel() {
    private val _ui = MutableStateFlow(RemoteUiState())
    val ui: StateFlow<RemoteUiState> = _ui.asStateFlow()

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val _update = MutableStateFlow<UpdateStatus?>(null)
    val update: StateFlow<UpdateStatus?> = _update.asStateFlow()

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
        stateJob = viewModelScope.launch { c.state.collect { _ui.value = it } }
        refresh()
    }

    private fun act(block: suspend RemoteController.() -> Unit) {
        val c = controller ?: return
        viewModelScope.launch { actions.withLock { c.block() } }
    }

    fun refresh() = act { refresh() }
    fun powerToggle() = act { powerToggle() }
    fun volumeUp() = act { volumeUp() }
    fun volumeDown() = act { volumeDown() }
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
}
