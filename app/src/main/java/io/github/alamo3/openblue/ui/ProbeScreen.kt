package io.github.alamo3.openblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.BluelinkClient

/**
 * Two references first: a path that surely doesn't exist (what "no such endpoint" looks like)
 * and one that does (vhcllst). Then guesses at a send-to-car endpoint, in Canada's naming style
 * and the US and European paths.
 */
private val DEFAULT_PATHS = listOf(
    "zzznotarealendpoint", "vhcllst",
    "sndpoi", "sendpoi", "sndpoi2car", "sndpoitocar", "snd2car", "sndtocar", "sendtocar", "stc", "sndstc",
    "evc/sndpoi", "v2/sndpoi", "rmt/sndpoi", "poi/send", "sndwypnt", "sndrt", "sndroute", "sndnav", "sndlctn",
    "rcs/stc/sendPoi", "ac/v2/rcs/stc/sendPoi", "location/routes", "v1/location/routes",
).joinToString("\n")

/** Developer: looks for endpoints the official app doesn't call, starting with send-to-car. */
@Composable
fun ProbeScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    var paths by rememberSaveable { mutableStateOf(DEFAULT_PATHS) }
    var authorise by rememberSaveable { mutableStateOf(true) }
    val results = vm.probeResults
    val notFound = results.firstOrNull { it.path == "zzznotarealendpoint" }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Endpoint probe", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp)) {
            Text(
                "Posts a send-to-car request (destination: the car's own location) to each path on mybluelink.ca " +
                    "and shows the answer. The first two are references: a made-up path and one that exists. " +
                    "Anything that answers differently from the made-up one is worth a look.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = paths,
                onValueChange = { paths = it },
                label = { Text("Paths, one per line") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            SettingSwitch("Authorise with PIN", "Adds the PIN check remote commands need", authorise) { authorise = it }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (vm.probing) scheme.primary.copy(alpha = 0.4f) else scheme.primary)
                    .pressable(!vm.probing) { vm.probeEndpoints(paths.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct(), authorise) }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (vm.probing) "Probing ${results.size + 1}…" else "Probe",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                )
            }

            SelectionContainer {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    results.forEach { r ->
                        val differs = notFound != null && r.path != notFound.path && !sameAnswer(r, notFound)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(if (differs) app.charge.copy(alpha = 0.14f) else scheme.surfaceContainerHigh)
                                .padding(10.dp),
                        ) {
                            Text(r.path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                            Text(
                                listOfNotNull(
                                    "HTTP ${r.http}",
                                    r.responseCode?.let { "responseCode $it" },
                                    r.errorCode?.let { "error $it" },
                                    r.message,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (r.body.isNotBlank()) {
                                Text(r.body, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 4)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sameAnswer(a: BluelinkClient.ProbeResult, b: BluelinkClient.ProbeResult) =
    a.http == b.http && a.responseCode == b.responseCode && a.errorCode == b.errorCode && a.message == b.message
