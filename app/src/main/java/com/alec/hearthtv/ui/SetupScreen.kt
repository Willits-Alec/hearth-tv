package com.alec.hearthtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alec.hearthtv.remote.PairingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SetupViewModel, onDone: () -> Unit, startStep: SetupViewModel.Step = SetupViewModel.Step.FIND_TV) {
    val s by vm.state.collectAsState()
    LaunchedEffect(startStep) { vm.startAt(startStep) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up Hearth TV", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)) },
        containerColor = Ink,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepHeader(s.step)
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ember, trackColor = Slate)
            s.message?.let { Text(it, color = PaperDim) }

            when (s.step) {
                SetupViewModel.Step.FIND_TV -> FindTvStep(s, vm)
                SetupViewModel.Step.PAIR -> PairStep(s, vm)
                SetupViewModel.Step.FIND_SONOS -> FindSonosStep(s, vm)
                SetupViewModel.Step.FIND_ROKU -> FindRokuStep(s, vm)
                SetupViewModel.Step.DONE -> DoneStep(s, onDone)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepHeader(step: SetupViewModel.Step) {
    val (n, title) = when (step) {
        SetupViewModel.Step.FIND_TV -> 1 to "Find the TV"
        SetupViewModel.Step.PAIR -> 2 to "Pair with the TV"
        SetupViewModel.Step.FIND_SONOS -> 3 to "Find the Sonos"
        SetupViewModel.Step.FIND_ROKU -> 4 to "Find the Roku"
        SetupViewModel.Step.DONE -> 5 to "All set"
    }
    Text("Step $n of 5", color = Ember, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FindTvStep(s: SetupViewModel.State, vm: SetupViewModel) {
    Text("Make sure the TV is on and this phone is on the same Wi-Fi as the TV.", color = PaperDim)
    s.foundTvs.forEach { tv ->
        Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sony ${tv.model}", fontWeight = FontWeight.SemiBold)
                    Text(tv.host, color = PaperDim, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.chooseTv(tv) }, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Use this TV") }
            }
        }
    }
    OutlinedButton(onClick = { vm.searchTv() }, enabled = !s.busy) { Text("Search again") }
    ManualAddress(label = "Or type the TV's address", hint = "e.g. 192.168.1.20", enabled = !s.busy) { vm.useTvAddress(it) }
}

@Composable
private fun PairStep(s: SetupViewModel.State, vm: SetupViewModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    Text("The TV shows a 4-digit code on its screen. Type it here.", color = PaperDim)
    s.tv?.let { Text("Sony ${it.model} · ${it.host}", style = MaterialTheme.typography.bodySmall, color = PaperDim) }
    when (val p = s.pairing) {
        is PairingState.NeedsPairing -> if (!p.pinShown) Text("No code yet — tap “Show the code again”.", color = PaperDim)
        is PairingState.Failed -> Text(p.reason, color = Bad)
        else -> Unit
    }
    OutlinedTextField(
        value = pin, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
        label = { Text("Code on the TV") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineMedium,
    )
    Button(
        onClick = { vm.submitPin(pin) }, enabled = pin.length == 4 && !s.busy, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink),
    ) { Text("Pair") }
    TextButton(onClick = { vm.startPairing() }, enabled = !s.busy) { Text("Show the code again") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }, enabled = !s.busy) { Text(if (advanced) "Hide the key option" else "No code on the TV? Use a pre-shared key") }
    if (advanced) {
        Text(
            "On the TV: Settings → Network & Internet → Home network setup → IP control → set Authentication to “Normal and Pre-Shared Key”, then type that key here.",
            color = PaperDim, style = MaterialTheme.typography.bodySmall,
        )
        ManualAddress(label = "Pre-shared key", hint = "the key shown on the TV", enabled = !s.busy, onSubmit = { vm.usePsk(it) })
    }
}

@Composable
private fun FindSonosStep(s: SetupViewModel.State, vm: SetupViewModel) {
    Text("Pick the sound bar connected to the TV. Volume buttons will drive it directly.", color = PaperDim)
    s.foundSonos.forEach { p ->
        Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${p.room} · ${p.model}", fontWeight = FontWeight.SemiBold)
                    Text(if (p.isHomeTheatre) "Sound bar" else "Speaker", color = PaperDim, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.chooseSonos(p) }, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Use") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.searchSonos() }, enabled = !s.busy) { Text("Search again") }
        TextButton(onClick = { vm.skipSonos() }, enabled = !s.busy) { Text("Skip — no Sonos") }
    }
    ManualAddress(label = "Or type the sound bar's address", hint = "e.g. 192.168.1.30", enabled = !s.busy) { vm.useSonosAddress(it) }
}

@Composable
private fun FindRokuStep(s: SetupViewModel.State, vm: SetupViewModel) {
    Text("If a Roku is plugged into the TV, pick it here and the remote gets a Roku section.", color = PaperDim)
    s.foundRokus.forEach { r ->
        Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.name} · ${r.model}", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (r.limited) "Control is switched off on the box — the remote will show how to turn it on" else r.host,
                        color = if (r.limited) Bad else PaperDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { vm.chooseRoku(r) }, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Use") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.searchRoku() }, enabled = !s.busy) { Text("Search again") }
        TextButton(onClick = { vm.skipRoku() }, enabled = !s.busy) { Text("Skip — no Roku") }
    }
    ManualAddress(label = "Or type the Roku's address", hint = "e.g. 192.168.1.91", enabled = !s.busy) { vm.useRokuAddress(it) }
}

@Composable
private fun DoneStep(s: SetupViewModel.State, onDone: () -> Unit) {
    Text("TV: Sony ${s.tv?.model ?: ""}", color = Paper)
    Text("Sound: ${s.sonos?.let { "${it.room} · ${it.model}" } ?: "TV only"}", color = Paper)
    Text("You can change any of this later from the gear on the remote.", color = PaperDim)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Open the remote") }
}

@Composable
private fun ManualAddress(label: String, hint: String, enabled: Boolean, onSubmit: (String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    Text(label, color = PaperDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text(hint) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedButton(onClick = { onSubmit(host) }, enabled = enabled && host.isNotBlank()) { Text("Check") }
    }
}
