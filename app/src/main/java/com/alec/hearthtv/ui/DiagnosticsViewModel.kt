package com.alec.hearthtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alec.hearthtv.BuildConfig
import com.alec.hearthtv.HearthGraph
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.diagnostics.ErrorEntry
import com.alec.hearthtv.diagnostics.SelfTestReport
import com.alec.hearthtv.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiagnosticsViewModel : ViewModel() {
    data class State(
        val settings: AppSettings? = null,
        val transport: String = "",
        val running: Boolean = false,
        val report: SelfTestReport? = null,
        val update: UpdateStatus? = null,
        val errors: List<ErrorEntry> = emptyList(),
        val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            HearthGraph.settings.settings.collect { s ->
                _state.update { it.copy(settings = s, transport = HearthGraph.transport.description) }
            }
        }
        viewModelScope.launch {
            HearthGraph.errorLog.entries.collect { e -> _state.update { it.copy(errors = e) } }
        }
    }

    fun runSelfTest() = viewModelScope.launch {
        val s = HearthGraph.settings.current()
        val test = HearthGraph.selfTest(s)
        if (test == null) {
            _state.update { it.copy(report = null) }
            return@launch
        }
        _state.update { it.copy(running = true, transport = HearthGraph.transport.description) }
        val report = test.run()
        _state.update { it.copy(running = false, report = report) }
    }

    fun checkUpdate() = viewModelScope.launch {
        _state.update { it.copy(update = HearthGraph.updateChecker().check()) }
    }

    fun clearErrors() = HearthGraph.errorLog.clear()

    fun forgetEverything() = viewModelScope.launch { HearthGraph.settings.forgetAll() }

    /** Diagnostics text with the recent errors and the self-test report appended, for the Copy button. */
    fun copyText(): String {
        val s = _state.value
        return buildString {
            appendLine("Hearth TV ${s.appVersion}")
            appendLine("network: ${s.transport}")
            s.settings?.let { st ->
                appendLine("tv: ${st.tvModel ?: "?"} @ ${st.tvHost ?: "not set"} mac=${st.tvMac ?: "?"} paired=${st.cookie != null || st.psk != null}")
                appendLine("sonos: ${st.sonosName ?: "not set"} @ ${st.sonosHost ?: "-"}")
                appendLine("roku: ${st.rokuHost ?: "not set"}")
            }
            appendLine("update: ${s.update ?: "not checked"}")
            appendLine()
            appendLine("recent errors:")
            appendLine(HearthGraph.errorLog.text())
            s.report?.let { appendLine(); append(it.text()) }
        }
    }
}
