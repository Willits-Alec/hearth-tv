package com.alec.hearthtv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alec.hearthtv.HearthGraph
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.net.WifiLanTransport
import com.alec.hearthtv.protocol.SsdpDiscovery
import com.alec.hearthtv.protocol.bravia.BraviaException
import com.alec.hearthtv.protocol.roku.RokuException
import com.alec.hearthtv.remote.PairingState
import com.alec.hearthtv.remote.RemoteController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FoundTv(val host: String, val model: String)
data class FoundSonos(val host: String, val model: String, val room: String, val isHomeTheatre: Boolean)
data class FoundRoku(val host: String, val name: String, val model: String, val limited: Boolean)

/** The first-launch wizard: find the TV → pair → find the Sonos → done. Every step also takes a typed address. */
class SetupViewModel : ViewModel() {
    enum class Step { FIND_TV, PAIR, FIND_SONOS, FIND_ROKU, DONE }

    data class State(
        val step: Step = Step.FIND_TV,
        val busy: Boolean = false,
        val message: String? = null,
        val foundTvs: List<FoundTv> = emptyList(),
        val tv: FoundTv? = null,
        val pairing: PairingState = PairingState.Unknown,
        val foundSonos: List<FoundSonos> = emptyList(),
        val sonos: FoundSonos? = null,
        val foundRokus: List<FoundRoku> = emptyList(),
        val roku: FoundRoku? = null,
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
            it.copy(busy = false, foundTvs = tvs, message = if (tvs.isEmpty()) "No Sony TV answered. Is the TV on and this phone on the home Wi-Fi? You can type its address below.${vpnNote()}" else null)
        }
    }

    fun useTvAddress(host: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Checking $host…") }
        val result = probeTvResult(host.trim())
        result.onSuccess { chooseTv(it) }.onFailure { e ->
            _state.update { it.copy(busy = false, message = "Couldn't reach a Sony TV at $host: ${describe(e)}${vpnNote()}") }
        }
    }

    private suspend fun probeTv(host: String): FoundTv? = probeTvResult(host).getOrNull()

    private suspend fun probeTvResult(host: String): Result<FoundTv> = runCatching {
        FoundTv(host, HearthGraph.bravia(host).interfaceInfo().modelName)
    }

    /** The one thing that silently breaks LAN access on a phone: a VPN that is not bypassable. */
    private fun vpnNote(): String {
        val t = HearthGraph.transport as? WifiLanTransport ?: return ""
        return if (t.vpnActive()) "\n\n${WifiLanTransport.VPN_HINT}" else ""
    }

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

    /** D2's advanced fallback: a Pre-Shared Key from the TV's IP-control settings, verified before it is kept. */
    fun usePsk(key: String) = viewModelScope.launch {
        val host = _state.value.tv?.host ?: return@launch
        val psk = key.trim()
        if (psk.isEmpty()) return@launch
        _state.update { it.copy(busy = true, message = null) }
        val probe = runCatching { HearthGraph.bravia(host, AppSettings(clientId = "", psk = psk)).systemInfo() }
        if (probe.isSuccess) {
            HearthGraph.settings.update { it.copy(psk = psk, cookie = null, cookieExpiresAt = null) }
            _state.update { it.copy(busy = false, pairing = PairingState.Paired, step = Step.FIND_SONOS) }
            searchSonos()
        } else {
            _state.update {
                it.copy(busy = false, pairing = PairingState.Failed("The TV rejected that key. On the TV: Settings → Network & Internet → Home network setup → IP control → Authentication must be “Normal and Pre-Shared Key”, and the key must match."))
            }
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
        _state.update { it.copy(sonos = p, step = Step.FIND_ROKU, busy = false, message = null) }
        searchRoku()
    }

    fun skipSonos() = viewModelScope.launch {
        HearthGraph.settings.update { it.copy(sonosHost = null, sonosName = null) }
        _state.update { it.copy(step = Step.FIND_ROKU, busy = false) }
        searchRoku()
    }

    // ── step 4: the Roku ────────────────────────────────────────────────────────────────────────────

    fun searchRoku() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Looking for a Roku…") }
        val hosts = runCatching { HearthGraph.findRokus() }.getOrDefault(emptyList())
        val boxes = hosts.mapNotNull { probeRoku(it) }
        _state.update {
            it.copy(busy = false, foundRokus = boxes, message = if (boxes.isEmpty()) "No Roku answered. You can type its address, or skip this." else null)
        }
    }

    fun useRokuAddress(host: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Checking $host…") }
        val p = probeRoku(host.trim())
        if (p == null) _state.update { it.copy(busy = false, message = "No Roku answered at $host.") } else chooseRoku(p)
    }

    private suspend fun probeRoku(host: String): FoundRoku? = runCatching {
        val box = HearthGraph.roku(host)
        val info = box.deviceInfo()
        val limited = runCatching { box.apps() }.exceptionOrNull() is RokuException.LimitedMode
        FoundRoku(host, info.friendlyName, info.modelName, limited)
    }.getOrNull()

    fun chooseRoku(p: FoundRoku) = viewModelScope.launch {
        HearthGraph.settings.update { it.copy(rokuHost = p.host, rokuName = p.name) }
        _state.update { it.copy(roku = p, step = Step.DONE, busy = false, message = null) }
    }

    fun skipRoku() = viewModelScope.launch {
        HearthGraph.settings.update { it.copy(rokuHost = null, rokuName = null) }
        _state.update { it.copy(step = Step.DONE, busy = false) }
    }

    fun describe(e: Throwable): String = when (e) {
        is BraviaException.Unreachable -> "${RemoteController.WIFI_HINT} (${e.cause?.message ?: e.message})"
        else -> e.message ?: "something went wrong"
    }
}
