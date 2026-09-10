package com.alec.hearthtv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alec.hearthtv.BuildConfig
import com.alec.hearthtv.update.UpdateStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    onBack: () -> Unit,
    onRepair: () -> Unit,
    onOpenStep: (SetupViewModel.Step) -> Unit = {},
) {
    val s by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.checkUpdate() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
        containerColor = Ink,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Line("App", s.appVersion)
                    Line("Network", s.transport)
                    s.settings?.let { st ->
                        Line("TV", "${st.tvModel ?: "?"} · ${st.tvHost ?: "not set"}")
                        Line("Paired", if (st.cookie != null || st.psk != null) "yes" else "no")
                        Line("Sonos", st.sonosName?.let { "$it · ${st.sonosHost}" } ?: "not set")
                        Line("Roku", st.rokuName?.let { "$it · ${st.rokuHost}" } ?: st.rokuHost ?: "not set")
                    }
                    Line(
                        "Update",
                        when (val u = s.update) {
                            null -> "checking…"
                            UpdateStatus.UpToDate -> "up to date"
                            is UpdateStatus.Available -> "v${u.versionName} available"
                            is UpdateStatus.Unknown -> "could not check (${u.reason})"
                        },
                    )
                }
            }
            (s.update as? UpdateStatus.Available)?.let { u ->
                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl))) }, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) {
                    Text("Download v${u.versionName}")
                }
            }

            Text("Self-test", style = MaterialTheme.typography.titleMedium, color = PaperDim, modifier = Modifier.padding(top = 8.dp))
            Text("Runs the same checks the app's test-suite runs, against your real TV and Sonos. Copy the result and send it if something is wrong.", color = PaperDim, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.runSelfTest() }, enabled = !s.running, colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink)) { Text("Run self-test") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.copyText())) }) { Text("Copy report") }
            }
            if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ember, trackColor = Slate)
            s.report?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = if (r.allPassed) Slate else SlateLight)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.checks.forEach { c ->
                            Row {
                                Text(if (c.passed) "PASS" else "FAIL", color = if (c.passed) Good else Bad, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 10.dp))
                                Text("${c.name} — ${c.detail}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Text("Volume", style = MaterialTheme.typography.titleMedium, color = PaperDim, modifier = Modifier.padding(top = 8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show volume on the TV")
                        Text(
                            "Off: the app changes the Sonos directly, one step at a time, and the TV shows nothing. " +
                                "On: the TV changes it instead, so its own volume bar appears, but it moves in steps of two.",
                            color = PaperDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.settings?.volumeViaTv == true, onCheckedChange = { vm.setVolumeViaTv(it) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Recent errors", style = MaterialTheme.typography.titleMedium, color = PaperDim, modifier = Modifier.weight(1f))
                if (s.errors.isNotEmpty()) TextButton(onClick = { vm.clearErrors() }) { Text("Clear") }
            }
            if (s.errors.isEmpty()) {
                Text("None since the app was opened.", color = PaperDim, style = MaterialTheme.typography.bodySmall)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Slate)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.errors.forEach { e ->
                            Text("${e.at.replace('T', ' ')}  ·  ${e.source}", color = PaperDim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            Text(e.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("Setup", style = MaterialTheme.typography.titleMedium, color = PaperDim, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Add a device without pairing the TV again, or start the whole wizard over.",
                color = PaperDim, style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onOpenStep(SetupViewModel.Step.FIND_ROKU) }) {
                    Text(if (s.settings?.rokuHost == null) "Add the Roku" else "Change the Roku")
                }
                OutlinedButton(onClick = { onOpenStep(SetupViewModel.Step.FIND_SONOS) }) {
                    Text(if (s.settings?.sonosHost == null) "Add the Sonos" else "Change the Sonos")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRepair) { Text("Run setup again") }
                TextButton(onClick = { vm.forgetEverything(); onRepair() }) { Text("Forget everything") }
            }
            Text("Download page: ${BuildConfig.PAGE_URL}", color = PaperDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, color = PaperDim, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
