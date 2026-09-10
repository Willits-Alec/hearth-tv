package com.alec.hearthtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alec.hearthtv.HearthGraph
import com.alec.hearthtv.protocol.SsdpDiscovery
import com.alec.hearthtv.protocol.bravia.BraviaException
import com.alec.hearthtv.remote.PairingState
import com.alec.hearthtv.remote.RemoteController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FoundTv(val host: String, val model: String)
data class FoundSonos(val host: String, val model: String, val room: String, val isHomeTheatre: Boolean)

/** The first-launch wizard: find the TV → pair → find the Sonos → done. Every step also takes a typed address. */
class SetupViewModel : ViewModel() {
    enum class Step { FIND_TV, PAIR, FIND_SONOS, DONE }

    data class State(
        val step: Step = Step.FIND_TV,
        val busy: Boolean = false,
        val message: String? = null,
        val foundTvs: List<FoundTv> = emptyList(),
        val tv: FoundTv? = null,
        val pairing: PairingState = PairingState.Unknown,
        val foundSonos: List<FoundSonos> = emptyList(),
        val sonos: FoundSonos? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var controller: RemoteController? = null

    // ── step 1: the TV ──────────────────────────────────────────────────────────────────────────────

    fun searchTv() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Looking for the TV on this Wi-Fi…") }
        val found = runCatching {
            HearthGraph.withMulticast { HearthGraph.discovery().search(SsdpDiscovery.ST_SONY_SCALAR, timeoutMs = 3000) }
        }.getOrDefault(emptyList())
        val tvs = found.map { it.host }.distinct().mapNotNull { host -> probeTv(host) }
        _state.update {
            it.copy(busy = false, foundTvs = tvs, message = if (tvs.isEmpty()) "No Sony TV answered. Is the TV on and this phone on the home Wi-Fi? You can type its address below." else null)
        }
    }

    fun useTvAddress(host: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Checking $host…") }
        val tv = probeTv(host.trim())
        if (tv == null) {
            _state.update { it.copy(busy = false, message = "Nothing that speaks Sony's API answered at $host.") }
        } else {
            chooseTv(tv)
        }
    }

    private suspend fun probeTv(host: String): FoundTv? = runCatching {
        FoundTv(host, HearthGraph.bravia(host).interfaceInfo().modelName)
    }.getOrNull()

    fun chooseTv(tv: FoundTv) = viewModelScope.launch {
        _state.update { it.copy(busy = true, tv = tv) }
        val mac = runCatching { HearthGraph.bravia(tv.host).wolMac() }.getOrNull()
        HearthGraph.settings.update { it.copy(tvHost = tv.host, tvMac = mac, tvModel = tv.model) }
        controller = HearthGraph.controller(HearthGraph.settings.current())
        _state.update { it.copy(busy = false, step = Step.PAIR, message = null) }
        startPairing()
    }

    // ── step 2: pairing ─────────────────────────────────────────────────────────────────────────────

    fun startPairing() = viewModelScope.launch {
        val c = controller ?: return@launch
        _state.update { it.copy(busy = true, message = null) }
        val result = c.startPairing()
        _state.update { it.copy(busy = false, pairing = result) }
        if (result == PairingState.Paired) _state.update { it.copy(step = Step.FIND_SONOS) }
    }

    fun submitPin(pin: String) = viewModelScope.launch {
        val c = controller ?: return@launch
        _state.update { it.copy(busy = true) }
        val result = c.completePairing(pin)
        _state.update { it.copy(busy = false, pairing = result) }
        if (result == PairingState.Paired) {
            _state.update { it.copy(step = Step.FIND_SONOS) }
            searchSonos()
        }
    }

    // ── step 3: the Sonos ───────────────────────────────────────────────────────────────────────────

    fun searchSonos() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Looking for Sonos players…") }
        val found = runCatching {
            HearthGraph.withMulticast { HearthGraph.discovery().search(SsdpDiscovery.ST_SONOS, timeoutMs = 3000) }
        }.getOrDefault(emptyList())
        val players = found.map { it.host }.distinct().mapNotNull { host -> probeSonos(host) }
            .sortedByDescending { it.isHomeTheatre }
        _state.update {
            it.copy(busy = false, foundSonos = players, message = if (players.isEmpty()) "No Sonos answered. You can type the bar's address, or skip this." else null)
        }
    }

    fun useSonosAddress(host: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Checking $host…") }
        val p = probeSonos(host.trim())
        if (p == null) _state.update { it.copy(busy = false, message = "No Sonos player answered at $host.") } else chooseSonos(p)
    }

    private suspend fun probeSonos(host: String): FoundSonos? = runCatching {
        val d = HearthGraph.sonos(host).description()
        FoundSonos(host, d.modelName, d.roomName, d.isHomeTheatre)
    }.getOrNull()

    fun chooseSonos(p: FoundSonos) = viewModelScope.launch {
        HearthGraph.settings.update { it.copy(sonosHost = p.host, sonosName = "${p.room} · ${p.model}") }
        _state.update { it.copy(sonos = p, step = Step.DONE, busy = false, message = null) }
    }

    fun skipSonos() = viewModelScope.launch {
        HearthGraph.settings.update { it.copy(sonosHost = null, sonosName = null) }
        _state.update { it.copy(step = Step.DONE, busy = false) }
    }

    fun describe(e: Throwable): String = when (e) {
        is BraviaException.Unreachable -> RemoteController.WIFI_HINT
        else -> e.message ?: "something went wrong"
    }
}
