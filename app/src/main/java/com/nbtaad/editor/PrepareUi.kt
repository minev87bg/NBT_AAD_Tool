package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The light blue used for "Prepare headunit": the hub tile, and this page's own theme. */
internal val PrepareBlue = Color(0xFF3DAEDE)

/** This page's own theme, the way SettingsTheme and AddOnsTheme are each their own accent colour. */
@Composable
internal fun PrepareTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) {
        base.copy(
            primary = PrepareBlue, onPrimary = Color(0xFF042433),
            primaryContainer = Color(0xFF123648), onPrimaryContainer = Color(0xFFD6F2FF),
            secondaryContainer = Color(0xFF123648), onSecondaryContainer = Color(0xFFD6F2FF),
            surfaceTint = PrepareBlue,
        )
    } else {
        base.copy(
            primary = Color(0xFF13789E), onPrimary = Color.White,
            primaryContainer = Color(0xFFD7F0FB), onPrimaryContainer = Color(0xFF07314A),
            secondaryContainer = Color(0xFFD7F0FB), onSecondaryContainer = Color(0xFF07314A),
            surfaceTint = Color(0xFF13789E),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * "Prepare headunit": today, opening the head unit's SSH over the car's Ethernet gateway (ZGW); later, checking and
 * changing its own coding as well. Opening SSH is not done by this app itself: it hands off to the project's own
 * self-service tool (the one Sq00ky's own install guide points to), then checks whether SSH came up.
 */
@Composable
fun PrepareScreen(vm: MainViewModel) {
    BackHandler { vm.backToHub() }
    val uri = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        TextButton(onClick = { vm.backToHub() }) { Text("‹ WHAT DO YOU WANT TO DO?", fontSize = 12.sp) }
        Text("Prepare headunit", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(
            "Gets the head unit ready for Android Auto to work without problems. Many of the issues people report " +
                "trace back to a head unit whose coding leaves SSH closed or restricted.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        StatusRow("Car's Ethernet gateway (ZGW)", vm.detect.zgw, found = vm.detect.zgw != null)
        StatusRow("Head unit's SSH", if (vm.detect.ssh) "Open" else null, found = vm.detect.ssh)

        Spacer(Modifier.height(16.dp))
        when {
            vm.detect.ssh -> {
                Text("SSH is open. The head unit is ready for Android Auto.", fontSize = 13.sp, color = Green)
            }
            vm.detect.zgw != null -> {
                Text(
                    "OPEN SSH takes you to the project's self-service SSH tool in your browser. It opens SSH on the " +
                        "head unit until its next restart. Come back here and press CHECK AGAIN.",
                    fontSize = 12.sp, color = muted,
                )
                Button(
                    onClick = { openLink(uri, SSH_TOOL_URL) },
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                ) { Text("OPEN SSH…") }
            }
            else -> {
                Text(
                    "Connect the car over its Ethernet port (an ENET/OBD cable, not the Android Auto WiFi) to prepare " +
                        "the head unit.",
                    fontSize = 12.sp, color = muted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { vm.recheckHeadUnit() },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.busy) "CHECKING…" else "CHECK AGAIN") }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (vm.feedback.isNotEmpty()) {
            Text(vm.feedback, fontSize = 11.sp, color = if (vm.feedbackError) Red else muted, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text("What Android Auto needs coded", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "Sq00ky's own project lists these as the head unit coding Android Auto needs, WiFi Direct included; " +
                "without them the car can refuse the phone's link outright. This reads them straight from the head " +
                "unit over the same Ethernet gateway — nothing is written, and the Vehicle Order is never touched.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { vm.checkCodings() },
            enabled = vm.detect.zgw != null && !vm.codingChecking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.codingChecking) "CHECKING CODINGS…" else "CHECK CODINGS") }
        if (vm.codingChecking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        val results = vm.codingResults
        if (results == null) {
            if (!vm.codingChecking) {
                Text(
                    "Not checked yet.",
                    fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            for ((group, bits) in results.groupBy { it.bit.group }) {
                Column(Modifier.padding(top = 14.dp)) {
                    Text(group, fontSize = 11.sp, color = muted, fontWeight = FontWeight.Medium)
                    for (r in bits) {
                        CodingRow(r)
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        CodingKeysSection(vm)

        Spacer(Modifier.height(24.dp))
        WriteCodingSection(vm)
        Spacer(Modifier.height(32.dp))
    }

    if (vm.confirmWriteCoding) {
        val fixes = vm.codingFixes
        AlertDialog(
            onDismissRequest = { vm.confirmWriteCoding = false },
            title = { Text("Write ${fixes.size} coding bit(s)?") },
            text = {
                Column {
                    Text("To car ${vm.detect.vin}. Each bit is set to the value Sq00ky's project lists as AKTIV:")
                    for ((bit, value) in fixes) {
                        Text("• ${bit.token} → $value", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        "The head unit will reset once this is written. The backup just read can revert this at any time.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyAaCoding() }) { Text("WRITE") } },
            dismissButton = { TextButton(onClick = { vm.confirmWriteCoding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CodingKeysSection(vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ncdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.importCodingKey(it, ncd = true) }
    }
    val l3Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.importCodingKey(it, ncd = false) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        u?.let { vm.recoverCodingKeysFromFolder(it) }
    }
    Text("Coding keys", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text(
        "Writing a coding bit needs two small keys recovered once from your own PSdZData — BMW's own dealer-level " +
            "diagnostic data, not something this app ships or can fetch for you. Point RECOVER ON THIS PHONE at the " +
            "psdzdata/security folder (or anything containing it, plus your PSdZ resource.jar) and both keys are " +
            "recovered right here, no PC or Python needed. They're kept on this phone encrypted, never sent anywhere.",
        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    Button(
        onClick = { folderPicker.launch(null) },
        enabled = !vm.recoveringKeys,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (vm.recoveringKeys) "RECOVERING…" else "RECOVER ON THIS PHONE…") }
    if (vm.recoveringKeys) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    Spacer(Modifier.height(10.dp))
    StatusRow("ncd_key.json (signs the coding)", if (vm.hasNcdKey) "Imported" else null, found = vm.hasNcdKey)
    Spacer(Modifier.height(8.dp))
    StatusRow("l3_key.json (unlocks writing)", if (vm.hasL3Key) "Imported" else null, found = vm.hasL3Key)
    Text(
        "Already have ncd_key.json / l3_key.json from a PC run instead? Import them directly:",
        fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    OutlinedButton(
        onClick = { ncdPicker.launch(arrayOf("application/json")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("IMPORT ncd_key.json…") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { l3Picker.launch(arrayOf("application/json")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("IMPORT l3_key.json…") }
    if (vm.hasNcdKey || vm.hasL3Key) {
        TextButton(onClick = { vm.forgetCodingKeys() }, modifier = Modifier.padding(top = 4.dp)) { Text("FORGET BOTH KEYS") }
    }
}

@Composable
private fun WriteCodingSection(vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Write coding", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text(
        "Reads a fresh backup, then — only for the bits CHECK CODINGS found not set, and only with both keys " +
            "imported — re-signs and writes them. Never touches the Vehicle Order. The backup can always be written " +
            "back afterward.",
        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    OutlinedButton(
        onClick = { vm.readCodingBackup() },
        enabled = vm.detect.zgw != null && !vm.codingBusy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (vm.codingBusy) "WORKING…" else "READ BACKUP") }
    if (vm.codingBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

    val backup = vm.codingBackup
    if (backup != null) {
        Text(
            if (backup.ok) "Backup read: ${backup.vin}, ${backup.mem?.size ?: 0} coding bytes."
            else "Backup read failed (${backup.steps.lastOrNull { !it.ok }?.detail ?: "see log"}).",
            fontSize = 12.sp, color = if (backup.ok) Green else Red, modifier = Modifier.padding(top = 8.dp),
        )
    }

    val fixes = vm.codingFixes
    if (backup?.ok == true) {
        if (fixes.isEmpty()) {
            Text(
                "Nothing to write — run CHECK CODINGS first, or everything it can confirm is already set.",
                fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text("Would write:", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 10.dp))
            for ((bit, value) in fixes) {
                Text("• ${bit.token} → $value", fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Button(
            onClick = { vm.confirmWriteCoding = true },
            enabled = vm.canWriteCoding,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        ) { Text("WRITE CODING…") }
        OutlinedButton(
            onClick = { vm.revertCoding() },
            enabled = vm.hasNcdKey && vm.hasL3Key && vm.codingBackup?.mem != null && !vm.codingBusy,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        ) { Text("REVERT TO BACKUP") }
    }

    vm.codingWriteLog?.let { log ->
        Column(Modifier.padding(top = 12.dp)) {
            Text("Last write:", fontSize = 12.sp, color = muted)
            for (step in log) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (step.ok) Green else Red))
                    Text("${step.name}: ${step.detail}", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CodingRow(r: com.nbtaad.editor.net.CodingResult) {
    val (dotColor, note) = when (r.status) {
        com.nbtaad.editor.net.CodingStatus.SET -> Green to "Set"
        com.nbtaad.editor.net.CodingStatus.NOT_SET -> Red to "Not set (reads as ${r.rawValue})"
        com.nbtaad.editor.net.CodingStatus.UNCERTAIN -> MaterialTheme.colorScheme.onSurfaceVariant to "Reads as ${r.rawValue} — check on the car"
        com.nbtaad.editor.net.CodingStatus.UNREADABLE -> MaterialTheme.colorScheme.onSurfaceVariant to "Couldn't read"
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.padding(start = 10.dp)) {
            Text("${r.bit.token} — ${r.bit.description}", fontSize = 13.sp)
            Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String?, found: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (found) Green else MaterialTheme.colorScheme.onSurfaceVariant))
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(value ?: "Not found", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sq00ky's own install guide points here for a temporary, self-service way to open SSH. */
internal const val SSH_TOOL_URL = "https://ssh.bavarian-aa.uk/"
