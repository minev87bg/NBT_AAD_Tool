package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.install.ReleaseInfo
import com.nbtaad.editor.net.JjAa

/**
 * "NBT Android Auto (original NBT)": installs JayJay3071's package, for the OLDER, non-Evo BMW NBT - a different
 * head unit from the rest of this app (see net/JjAa.kt). Deliberately its own screen, separate from Install/update
 * and Prepare headunit, so the two generations are never mixed up by mistake.
 */
@Composable
fun JjAaScreen(vm: MainViewModel) {
    BackHandler { vm.backToHub() }
    val c = vm.jjAa
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { c.usePicked(it) }
    }

    if (c.showCredits) {
        AlertDialog(
            onDismissRequest = { c.showCredits = false },
            confirmButton = { TextButton(onClick = { c.showCredits = false }) { Text("CLOSE") } },
            title = { Text("Credits / licenses") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                    SelectionContainer {
                        Text(JjAa.CREDITS, fontSize = 12.sp)
                    }
                    c.payload?.let { p ->
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Text(p.permissionText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Text("NBT Android Auto (original NBT)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(
            "JayJay3071's Android Auto / CarPlay package, for the ORIGINAL BMW NBT (2012/07-2015/09) - NOT NBT Evo, " +
                "which this app otherwise targets: NBT Evo uses a different DSI library and cannot run this. The app " +
                "checks the head unit before writing anything and refuses if it does not look like the right hardware.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = { c.showCredits = true }, modifier = Modifier.padding(top = 2.dp)) {
            Text("Credits / licenses", fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Head unit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = c.hostOverride, onValueChange = { c.hostOverride = it },
                    label = { Text("IP address (blank: ${c.effectiveHost})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = c.password, onValueChange = { c.password = it },
                    label = { Text("root password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text(
                    "SSH must already be open on this head unit (FeatureInstaller) - this screen only installs the " +
                        "package, same as the project's own installer does.",
                    fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Package", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (c.payload == null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                c.loading -> "Checking GitHub…"
                                c.loadMessage.isNotEmpty() -> c.loadMessage
                                c.checkedAt != 0L && c.releases.isEmpty() -> "No releases found."
                                else -> "Not checked yet."
                            },
                            fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { c.refresh() }, enabled = !c.loading) { Text("CHECK GITHUB", fontSize = 12.sp) }
                    }
                    if (c.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    for (r in c.releases.take(5)) ReleaseRow(r, enabled = !c.payloadBusy) { c.useRelease(r) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                        enabled = !c.payloadBusy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("IMPORT A RELEASE ZIP FROM THIS PHONE") }
                    if (c.payloadBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    c.payloadError?.let {
                        Text(it, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    val p = c.payload!!
                    Text("${p.manifest.version} - ${p.manifest.entries.size} files", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(c.payloadSource, fontSize = 11.sp, color = muted)
                    TextButton(onClick = { c.clearPayload() }) { Text("USE A DIFFERENT FILE", fontSize = 12.sp) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.install() }, enabled = c.payload != null && !c.busy, modifier = Modifier.weight(1f)) {
                Text("INSTALL / UPDATE")
            }
            OutlinedButton(onClick = { c.uninstall() }, enabled = !c.busy, modifier = Modifier.weight(1f)) {
                Text("UNINSTALL")
            }
        }
        if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        c.result?.let { r ->
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (c.resultOk) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                SelectionContainer { Text(r, Modifier.padding(12.dp), fontSize = 13.sp) }
            }
        }

        if (c.log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Log", fontSize = 12.sp, color = muted)
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        c.log.joinToString("\n"), Modifier.padding(10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReleaseRow(r: ReleaseInfo, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { r.tag }, fontSize = 13.sp)
            Text(
                r.tag + (if (r.prerelease) " · pre-release" else ""),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick, enabled = enabled) { Text("USE", fontSize = 12.sp) }
    }
}
