package com.alec.hearthtv.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alec.hearthtv.protocol.bravia.InputKind
import com.alec.hearthtv.protocol.bravia.SoundOutput
import com.alec.hearthtv.protocol.bravia.TvApp
import com.alec.hearthtv.protocol.bravia.TvInput
import com.alec.hearthtv.protocol.roku.RokuApp
import com.alec.hearthtv.remote.PadAction
import com.alec.hearthtv.remote.PairingState
import com.alec.hearthtv.remote.RemoteUiState
import com.alec.hearthtv.remote.RokuState
import com.alec.hearthtv.remote.SonosState
import com.alec.hearthtv.remote.TvState
import com.alec.hearthtv.remote.VolumeTarget
import com.alec.hearthtv.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FAVOURITES = listOf(
    "Prime Video", "YouTube", "Netflix", "Apple TV", "Hulu", "Paramount+", "Peacock TV", "Disney+", "Max",
    "Spotify", "Pandora", "Plex", "YouTube Music",
)

/** The Roku names its channels a little differently from the TV. */
private val ROKU_FAVOURITES = listOf(
    "Prime Video", "YouTube", "Netflix", "Paramount Plus", "Hulu", "Disney Plus", "Max", "Apple TV", "Peacock TV",
    "Plex - Free Movies & TV", "Pandora", "Spotify", "YouTube TV",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, onOpenSetup: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val update by vm.update.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    /** One tap = one key with a tick. Held keys (arrows, volume) tick on their own. */
    val press: (String) -> Unit = { haptic.tick(); vm.key(it) }

    // Quiet background refresh: no progress bar, no flicker. Immediately on resume, then every 8 s.
    LifecycleResumeEffect(Unit) {
        vm.refreshQuiet()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(8000)
            vm.refreshQuiet()
        }
    }
    // Volume alone, far more often, so the bar follows the Sonos app or a physical remote (SCOPE.md §9.3).
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            vm.refreshVolume()
        }
    }
    LaunchedEffect(ui.lastError) { ui.lastError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.voice(it) }
    }
    val startVoice: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a TV command — “volume up”, “open Prime”, “switch to Roku”")
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { scope.launch { snackbar.showSnackbar("This phone has no speech recogniser available.") } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hearth TV", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = startVoice) { Icon(Icons.Rounded.Mic, "Voice command") }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Rounded.Build, "Diagnostics") }
                    IconButton(onClick = onOpenSetup) { Icon(Icons.Rounded.Settings, "Setup") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(snackbarData = data, containerColor = SlateLight, contentColor = Paper) } },
        containerColor = Ink,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Ember, trackColor = Slate) else Spacer(Modifier.height(4.dp))

            (update as? UpdateStatus.Available)?.let { UpdateBanner(it) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

            StatusCard(ui, settings?.tvModel, onPower = { haptic.tick(); vm.powerToggle() })

            // Keep the remote usable from the last good read while the TV is briefly out of reach.
            val live = ui.tv as? TvState.On
            val shown = live ?: ui.lastKnown.takeIf { ui.tv is TvState.Unreachable || ui.tv is TvState.Unknown }

            when {
                shown != null -> {
                    if (live == null) ReconnectingBanner(ui.tv)
                    if (ui.pairing is PairingState.NeedsPairing) PairingCard(onOpenSetup)
                    VolumeCluster(ui, onDown = { vm.volumeDown() }, onMute = { haptic.tick(); vm.toggleMute() }, onUp = { vm.volumeUp() }, onSet = { vm.setVolumeLevel(it) })
                    InputsRow(shown.inputs, shown.nowPlaying) { vm.selectInput(it) }
                    AppsRow(shown.apps, shown.nowPlaying) { vm.launchApp(it) }
                    RokuCard(ui.roku, shown, touchpad = settings?.touchpad == true, onTouchpad = { vm.setTouchpad(it) }, onHome = { haptic.tick(); vm.rokuHome() }, onKey = { haptic.tick(); vm.rokuKey(it) }, onArrow = { vm.rokuKey(it) }, onLaunch = { haptic.tick(); vm.rokuLaunch(it) })
                    DPad(onKey = press, onArrow = { vm.key(it) }, touchpad = settings?.touchpad == true, onTouchpad = { vm.setTouchpad(it) })
                    MediaRow(onKey = press)
                    MoreKeys(onKey = press)
                    SoundCard(ui, shown, onFix = { vm.fixSound() }, onOutput = { vm.setOutput(it) }, onNight = { vm.setNightMode(it) }, onSpeech = { vm.setSpeechEnhancement(it) })
                    TypeCard(onSend = { vm.typeText(it) })
                }
                ui.tv == TvState.Standby -> Hint("The TV is off. Tap the power button to wake it.")
                ui.tv is TvState.Unreachable -> Hint((ui.tv as TvState.Unreachable).hint)
                else -> Hint(if (settings?.isConfigured == false) "No TV set up yet. Open Setup from the gear." else "Reading the TV…")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReconnectingBanner(tv: TvState) {
    val text = if (tv is TvState.Unreachable) "Reconnecting to the TV… buttons keep working as soon as it answers." else "Reading the TV…"
    Box(Modifier.fillMaxWidth().background(SlateLight, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PaperDim)
    }
}

@Composable
private fun UpdateBanner(u: UpdateStatus.Available, onDownload: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EmberDim)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${u.versionName} is available", fontWeight = FontWeight.SemiBold)
                u.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Paper) }
            }
            Button(onClick = { onDownload(u.apkUrl) }, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Download") }
        }
    }
}

@Composable
private fun StatusCard(ui: RemoteUiState, model: String?, onPower: () -> Unit) {
    val (line, sub) = when (val tv = ui.tv) {
        is TvState.On -> ("On" + (tv.nowPlaying?.let { " · $it" } ?: "")) to (model ?: "Sony TV")
        TvState.Standby -> "Standby" to (model ?: "Sony TV")
        is TvState.Unreachable -> (if (ui.reconnecting) "Reconnecting…" else "Not reachable") to tv.hint
        TvState.Unknown -> "…" to (model ?: "")
    }
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = PaperDim)
            }
            val on = ui.tv is TvState.On
            FilledIconButton(
                onClick = onPower,
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (on) SlateLight else Ember, contentColor = if (on) Paper else Ink),
            ) { Icon(Icons.Rounded.PowerSettingsNew, "Power", Modifier.size(32.dp)) }
        }
    }
}

@Composable
private fun PairingCard(onOpenSetup: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SlateLight)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("The TV needs pairing once before apps and keys work.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onOpenSetup) { Text("Pair") }
        }
    }
}

@Composable
private fun VolumeCluster(ui: RemoteUiState, onDown: () -> Unit, onMute: () -> Unit, onUp: () -> Unit, onSet: (Int) -> Unit) {
    val label = when (ui.volumeTarget) {
        VolumeTarget.SONOS -> "Sonos"
        VolumeTarget.TV -> if (ui.volumeViaTv) "the TV" else "TV speakers"
    }
    val level = ui.volumeLevel
    val muted = ui.volumeMuted
    val range = ui.volumeRange
    // While a finger is on the bar it owns the value; the device's own reading takes over again on release.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragValue else (level ?: range.first).toFloat()

    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "$label  ${shown.toInt()}${if (muted) "  muted" else ""}",
                    color = PaperDim, style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (level != null) {
                Slider(
                    value = shown,
                    onValueChange = { v ->
                        dragging = true
                        dragValue = v
                        onSet(v.toInt())
                    },
                    onValueChangeFinished = {
                        onSet(dragValue.toInt())
                        dragging = false
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Ember, activeTrackColor = Ember, inactiveTrackColor = SlateLight),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HoldButton(Icons.Rounded.VolumeDown, "Volume down", onDown, Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp))
                BigButton(Icons.Rounded.VolumeOff, if (muted) "Unmute" else "Mute", Modifier.weight(0.7f), onMute, tonal = true)
                HoldButton(Icons.Rounded.VolumeUp, "Volume up", onUp, Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp))
            }
        }
    }
}

/** The toggle in a pad card's top right: arrow buttons or the swipe pad (SCOPE.md §9.1). */
@Composable
private fun PadModeToggle(touchpad: Boolean, onTouchpad: (Boolean) -> Unit) {
    IconButton(onClick = { onTouchpad(!touchpad) }) {
        Icon(
            if (touchpad) Icons.Rounded.Gamepad else Icons.Rounded.OpenWith,
            if (touchpad) "Use the arrow buttons" else "Use the swipe pad",
            tint = if (touchpad) Ember else PaperDim,
        )
    }
}

/** IRCC key names for the TV's pad. */
private fun tvKeyFor(action: PadAction) = when (action) {
    PadAction.UP -> "Up"
    PadAction.DOWN -> "Down"
    PadAction.LEFT -> "Left"
    PadAction.RIGHT -> "Right"
    PadAction.OK -> "Confirm"
    PadAction.BACK -> "Return"
}

/** Roku ECP key names for the Roku's pad. */
private fun rokuKeyFor(action: PadAction) = when (action) {
    PadAction.UP -> "Up"
    PadAction.DOWN -> "Down"
    PadAction.LEFT -> "Left"
    PadAction.RIGHT -> "Right"
    PadAction.OK -> "Select"
    PadAction.BACK -> "Back"
}

@Composable
private fun BigButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit, tonal: Boolean = false) {
    if (tonal) {
        FilledTonalIconButton(onClick = onClick, modifier = modifier.height(64.dp), shape = RoundedCornerShape(16.dp)) { Icon(icon, description, Modifier.size(28.dp)) }
    } else {
        FilledIconButton(
            onClick = onClick, modifier = modifier.height(64.dp), shape = RoundedCornerShape(16.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = SlateLight, contentColor = Paper),
        ) { Icon(icon, description, Modifier.size(30.dp)) }
    }
}

@Composable
private fun InputsRow(inputs: List<TvInput>, nowPlaying: String?, onSelect: (String) -> Unit) {
    // Devices by their CEC name first; raw HDMI ports only when something is plugged in and no device claimed the port.
    val cec = inputs.filter { it.kind == InputKind.CEC_DEVICE }
    val ports = inputs.filter { p ->
        p.kind == InputKind.HDMI && cec.none { c -> c.port == p.port } && (p.connected || p.active || cec.isEmpty())
    }
    val shown = cec + ports
    if (shown.isEmpty()) return
    Section("Inputs")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(shown, key = { it.uri }) { input ->
            val active = input.active || (nowPlaying != null && nowPlaying == input.displayName)
            FilterChip(selected = active, onClick = { onSelect(input.uri) }, label = { Text(input.displayName) })
        }
    }
}

@Composable
private fun AppsRow(apps: List<TvApp>, nowPlaying: String?, onLaunch: (String) -> Unit) {
    if (apps.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val favourites = FAVOURITES.mapNotNull { name -> apps.firstOrNull { it.title.equals(name, ignoreCase = true) } }
    val list = if (showAll) apps.sortedBy { it.title.lowercase() } else favourites.ifEmpty { apps.take(8) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Section("Apps", Modifier.weight(1f))
        OutlinedButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Favourites" else "All ${apps.size}") }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(list, key = { it.uri }) { app ->
            FilterChip(selected = nowPlaying == app.title, onClick = { onLaunch(app.uri) }, label = { Text(app.title) })
        }
    }
}

@Composable
private fun DPad(onKey: (String) -> Unit, onArrow: (String) -> Unit, touchpad: Boolean, onTouchpad: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Section("Navigate", Modifier.weight(1f))
        PadModeToggle(touchpad, onTouchpad)
    }
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (touchpad) {
                Touchpad(onAction = { onKey(tvKeyFor(it)) })
            } else {
            ArrowKey(Icons.Rounded.KeyboardArrowUp, "Up", onArrow)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArrowKey(Icons.Rounded.KeyboardArrowLeft, "Left", onArrow)
                Button(
                    onClick = { onKey("Confirm") }, modifier = Modifier.size(84.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink),
                ) { Text("OK", fontWeight = FontWeight.Bold) }
                ArrowKey(Icons.Rounded.KeyboardArrowRight, "Right", onArrow)
            }
            ArrowKey(Icons.Rounded.KeyboardArrowDown, "Down", onArrow)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallKey(Icons.Rounded.ArrowBack, "Back", Modifier.weight(1f)) { onKey("Return") }
                SmallKey(Icons.Rounded.Home, "Home", Modifier.weight(1f)) { onKey("Home") }
                SmallKey(Icons.Rounded.Menu, "Menu", Modifier.weight(1f)) { onKey("ActionMenu") }
                SmallKey(Icons.Rounded.Info, "Info", Modifier.weight(1f)) { onKey("Display") }
            }
        }
    }
}

// ── Roku (Stage 2b) ─────────────────────────────────────────────────────────────────────────────────

/**
 * The Roku section: its name and what it is showing, a Home / Switch-to-Roku button, and — once the Roku is on
 * screen — its own D-pad, transport keys and channel list. Limited mode and an unreachable box explain themselves.
 */
@Composable
private fun RokuCard(roku: RokuState, tv: TvState.On, touchpad: Boolean, onTouchpad: (Boolean) -> Unit, onHome: () -> Unit, onKey: (String) -> Unit, onArrow: (String) -> Unit, onLaunch: (String) -> Unit) {
    if (roku is RokuState.Absent) return
    val rokuInput = tv.inputs.firstOrNull { it.kind == InputKind.CEC_DEVICE && it.title.contains("roku", ignoreCase = true) }
    val inFront = rokuInput != null && (rokuInput.active || tv.nowPlaying == rokuInput.displayName)
    var forceOpen by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Section("Roku", Modifier.weight(1f))
        if (roku is RokuState.Ready) PadModeToggle(touchpad, onTouchpad)
    }
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (roku) {
                is RokuState.Unreachable -> Text(roku.hint, color = Bad, style = MaterialTheme.typography.bodySmall)
                is RokuState.Limited -> Text(roku.hint, color = Bad, style = MaterialTheme.typography.bodySmall)
                is RokuState.Ready -> {
                    val showing = if (roku.onHome) "Home screen" else roku.activeApp ?: "…"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(roku.name, style = MaterialTheme.typography.titleMedium)
                            Text(showing + if (inFront) "" else " · not on the TV yet", color = PaperDim, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = onHome, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) {
                            Text(if (inFront) "Home" else "Switch to Roku")
                        }
                    }
                    if (inFront || forceOpen) {
                        if (touchpad) Touchpad(onAction = { onKey(rokuKeyFor(it)) }) else RokuPad(onKey, onArrow)
                        RokuApps(roku.apps, roku.activeApp, onLaunch)
                    } else {
                        TextButton(onClick = { forceOpen = true }) { Text("Show the Roku controls anyway") }
                    }
                }
                RokuState.Absent -> Unit
            }
        }
    }
}

@Composable
private fun RokuPad(onKey: (String) -> Unit, onArrow: (String) -> Unit) {
    val arrow = Modifier.size(width = 84.dp, height = 48.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HoldButton(Icons.Rounded.KeyboardArrowUp, "Up", { onArrow("Up") }, arrow)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HoldButton(Icons.Rounded.KeyboardArrowLeft, "Left", { onArrow("Left") }, arrow)
            Button(
                onClick = { onKey("Select") }, modifier = Modifier.size(72.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = SlateLight, contentColor = Paper),
            ) { Text("OK", fontWeight = FontWeight.Bold) }
            HoldButton(Icons.Rounded.KeyboardArrowRight, "Right", { onArrow("Right") }, arrow)
        }
        HoldButton(Icons.Rounded.KeyboardArrowDown, "Down", { onArrow("Down") }, arrow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallKey(Icons.Rounded.ArrowBack, "Back", Modifier.weight(1f)) { onKey("Back") }
            SmallKey(Icons.Rounded.Replay, "Replay", Modifier.weight(1f)) { onKey("InstantReplay") }
            SmallKey(Icons.Rounded.FastRewind, "Rewind", Modifier.weight(1f)) { onKey("Rev") }
            SmallKey(Icons.Rounded.PlayArrow, "Play / Pause", Modifier.weight(1f)) { onKey("Play") }
            SmallKey(Icons.Rounded.FastForward, "Forward", Modifier.weight(1f)) { onKey("Fwd") }
            SmallKey(Icons.Rounded.Info, "Options", Modifier.weight(1f)) { onKey("Info") }
        }
    }
}

@Composable
private fun RokuApps(apps: List<RokuApp>, active: String?, onLaunch: (String) -> Unit) {
    if (apps.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val favourites = ROKU_FAVOURITES.mapNotNull { name -> apps.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val list = if (showAll) apps.sortedBy { it.name.lowercase() } else favourites.ifEmpty { apps.take(8) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Roku channels", color = PaperDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Favourites" else "All ${apps.size}") }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(list, key = { it.id }) { app ->
            FilterChip(selected = active == app.name, onClick = { onLaunch(app.id) }, label = { Text(app.name) })
        }
    }
}

/** A D-pad arrow: sends its IRCC key on press and repeats while held (SCOPE.md §4.2). */
@Composable
private fun ArrowKey(icon: ImageVector, key: String, onArrow: (String) -> Unit) {
    HoldButton(icon, key, { onArrow(key) }, Modifier.size(width = 84.dp, height = 56.dp))
}

/** The rest of the Sony remote, folded away: guide, input, exit, channels, numbers, app keys, colours. */
@Composable
private fun MoreKeys(onKey: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Section("More keys", Modifier.weight(1f))
        OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
    }
    if (!open) return
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyRow(listOf("Guide" to "GGuide", "Input" to "Input", "Exit" to "Exit", "Options" to "Options"), onKey)
            KeyRow(listOf("Ch +" to "ChannelUp", "Ch -" to "ChannelDown", "Subtitles" to "SubTitle", "Audio" to "Audio"), onKey)
            KeyRow(listOf("1" to "Num1", "2" to "Num2", "3" to "Num3"), onKey)
            KeyRow(listOf("4" to "Num4", "5" to "Num5", "6" to "Num6"), onKey)
            KeyRow(listOf("7" to "Num7", "8" to "Num8", "9" to "Num9"), onKey)
            KeyRow(listOf("Netflix" to "Netflix", "0" to "Num0", "YouTube" to "YouTube"), onKey)
            KeyRow(listOf("Red" to "Red", "Green" to "Green", "Yellow" to "Yellow", "Blue" to "Blue"), onKey)
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, code) ->
            FilledTonalButton(onClick = { onKey(code) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Text(label) }
        }
    }
}

@Composable
private fun SmallKey(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp)) {
        Icon(icon, description)
    }
}

@Composable
private fun MediaRow(onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallKey(Icons.Rounded.FastRewind, "Rewind", Modifier.weight(1f)) { onKey("Rewind") }
        SmallKey(Icons.Rounded.PlayArrow, "Play", Modifier.weight(1f)) { onKey("Play") }
        SmallKey(Icons.Rounded.Pause, "Pause", Modifier.weight(1f)) { onKey("Pause") }
        SmallKey(Icons.Rounded.Stop, "Stop", Modifier.weight(1f)) { onKey("Stop") }
        SmallKey(Icons.Rounded.FastForward, "Forward", Modifier.weight(1f)) { onKey("Forward") }
    }
}

@Composable
private fun SoundCard(ui: RemoteUiState, tv: TvState.On, onFix: () -> Unit, onOutput: (SoundOutput) -> Unit, onNight: (Boolean) -> Unit, onSpeech: (Boolean) -> Unit) {
    val sonos = ui.sonos
    Section("Sound")
    Card(colors = CardDefaults.cardColors(containerColor = Slate), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TV sound goes to", style = MaterialTheme.typography.bodyMedium, color = PaperDim)
                    Text(
                        when (tv.output) {
                            SoundOutput.AUDIO_SYSTEM -> "Sonos (Audio system)"
                            SoundOutput.TV_SPEAKER -> "TV speakers"
                            SoundOutput.HDMI -> "HDMI"
                            SoundOutput.TV_SPEAKER_AND_HDMI -> "TV speakers + HDMI"
                            null -> "…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                FilledTonalButton(onClick = { onOutput(if (tv.output == SoundOutput.AUDIO_SYSTEM) SoundOutput.TV_SPEAKER else SoundOutput.AUDIO_SYSTEM) }) {
                    Text(if (tv.output == SoundOutput.AUDIO_SYSTEM) "Use TV speakers" else "Use Sonos")
                }
            }
            Button(onClick = onFix, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) {
                Text("Fix the sound", fontWeight = FontWeight.SemiBold)
            }
            when (sonos) {
                is SonosState.Ready -> {
                    ToggleRow(Icons.Rounded.Bedtime, "Night sound", sonos.nightMode, onNight)
                    ToggleRow(Icons.Rounded.RecordVoiceOver, "Speech enhancement", sonos.speechEnhancement, onSpeech)
                    Text("Sonos source: ${sonos.source.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = PaperDim)
                }
                is SonosState.Unreachable -> Text(sonos.hint, style = MaterialTheme.typography.bodySmall, color = Bad)
                SonosState.Absent -> Text("No Sonos set up. Add it in Setup to control volume directly.", style = MaterialTheme.typography.bodySmall, color = PaperDim)
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = PaperDim)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TypeCard(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Section("Type on the TV")
    Text(
        "Works while a text box is open on the TV — a search field or a sign-in screen. Open it with the remote first, then send.",
        style = MaterialTheme.typography.bodySmall, color = PaperDim,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Search box, password…") },
        )
        FilledIconButton(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Send, "Send to TV")
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, color = PaperDim)
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().background(Slate, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text(text, color = PaperDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
