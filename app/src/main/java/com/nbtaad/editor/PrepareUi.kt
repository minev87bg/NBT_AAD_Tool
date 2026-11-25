package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * "Prepare headunit": checking (and fixing) the handful of coding bits Android Auto needs, and opening the head
 * unit's SSH over the car's Ethernet gateway (ZGW) so Sq00ky's own project can be installed. This page is reachable
 * straight from the Hub, car or no car yet: CHECK CODINGS does both at once - detecting the car (or re-detecting
 * it) and reading the coding bits - so there is no separate "detect" step before it. Signing tools (the two coding
 * keys WRITE CODING and REVERT TO BACKUP need) live on their own page, reached only when they are still needed.
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
            "A few of this head unit's own coding bits are essential for Android Auto to work at all, SSH " +
                "included. Follow the page below: check the codings, fix whatever isn't set, and open SSH if " +
                "it's still closed.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { vm.checkCodings() },
            enabled = !vm.codingChecking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.codingChecking) "CHECKING…" else "CHECK CODINGS") }
        if (vm.codingChecking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        if (vm.codingResults != null || !vm.codingChecking) {
            // UNKNOWN covers both "never checked" and "the last check couldn't read every bit cleanly" -
            // a failed read is not evidence a coding is unset, so neither one renders as a NOT SET verdict;
            // both get the same bold weight, since either way the real answer is to tap CHECK CODINGS.
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (vm.codingCheckState) {
                        MainViewModel.CodingCheckState.PASSED -> "CODING PASSED"
                        MainViewModel.CodingCheckState.NOT_SET -> "CODINGS NOT SET"
                        MainViewModel.CodingCheckState.UNKNOWN -> "CODING STATUS UNKNOWN"
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = if (vm.codingCheckState == MainViewModel.CodingCheckState.PASSED) Green else Red,
                )
                Spacer(Modifier.width(8.dp))
                InfoBadge(onClick = { vm.showCodingDetail = true })
            }
        }
        if (vm.feedback.isNotEmpty()) {
            Text(vm.feedback, fontSize = 11.sp, color = if (vm.feedbackError) Red else muted, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(18.dp))
        StatusRow("Vehicle's Ethernet gateway (ZGW)", vm.detect.zgw, found = vm.detect.zgw != null)
        StatusRow("Head unit's SSH", if (vm.detect.ssh) "Open" else null, found = vm.detect.ssh)

        Spacer(Modifier.height(12.dp))
        when {
            vm.detect.ssh -> {
                Text("SSH is open.", fontSize = 13.sp, color = Green)
                StarterCfgResearchSection(vm)
            }
            vm.detect.zgw != null -> {
                Text(
                    "OPEN SSH takes you to the project's self-service SSH tool in your browser. It opens SSH on the " +
                        "head unit until its next restart. Come back here and press CHECK CODINGS.",
                    fontSize = 12.sp, color = muted,
                )
                Button(
                    onClick = { openLink(uri, SSH_TOOL_URL) },
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                ) { Text("OPEN SSH…") }
            }
            else -> {
                Text(
                    "Connect the vehicle over its Ethernet port (an ENET/OBD cable, not the Android Auto WiFi) to prepare " +
                        "the head unit.",
                    fontSize = 12.sp, color = muted,
                )
            }
        }

        if (!(vm.hasNcdKey && vm.hasL3Key)) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.screen = Screen.SIGNING_TOOLS },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SIGNING TOOLS NOT SET UP — TAP TO SET UP") }
        }

        Spacer(Modifier.height(20.dp))
        WriteCodingSection(vm)
        Spacer(Modifier.height(32.dp))
    }

    if (vm.showCodingDetail) {
        AlertDialog(
            onDismissRequest = { vm.showCodingDetail = false },
            title = { Text("Coding check") },
            text = { CodingCheckConsole(vm.codingResults ?: emptyList(), maxHeight = 340.dp) },
            confirmButton = { TextButton(onClick = { vm.showCodingDetail = false }) { Text("CLOSE") } },
        )
    }

    if (vm.showCodingWriteLog) {
        AlertDialog(
            onDismissRequest = { vm.showCodingWriteLog = false },
            title = { Text("Write log") },
            text = { WriteLogConsole(vm.codingWriteLog ?: emptyList(), maxHeight = 340.dp) },
            confirmButton = { TextButton(onClick = { vm.showCodingWriteLog = false }) { Text("CLOSE") } },
        )
    }

    if (vm.confirmWriteCoding) {
        val fixes = vm.codingFixes
        AlertDialog(
            onDismissRequest = { vm.confirmWriteCoding = false },
            title = { Text("Write ${fixes.size} coding bit(s)?") },
            text = {
                Column {
                    Text("To vehicle ${vm.detect.vin}. Each bit is set to the value Sq00ky's project lists as AKTIV:")
                    for ((bit, value) in fixes) {
                        Text("• ${bit.token} → $value", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        "The head unit will reset once this is written. The backup just read can revert this at any time.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                    )
                    Text("Clear DTC?", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
                    for ((choice, label) in listOf(
                        MainViewModel.ClearDtcChoice.DONT to "Don't",
                        MainViewModel.ClearDtcChoice.HEAD_UNIT_ONLY to "Only HeadUnit",
                        MainViewModel.ClearDtcChoice.WHOLE_VEHICLE to "Whole Vehicle",
                    )) {
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.clearDtcChoice = choice },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.clearDtcChoice == choice, onClick = { vm.clearDtcChoice = choice })
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyAaCoding() }) { Text("WRITE") } },
            dismissButton = { TextButton(onClick = { vm.confirmWriteCoding = false }) { Text("Cancel") } },
        )
    }
}

/**
 * "Signing tools": loading (or importing) the two coding keys WRITE CODING and REVERT TO BACKUP need to sign
 * what they send. Its own page, off Prepare headunit, since once both keys are loaded there is nothing left to
 * show here - Prepare headunit only links to this page while a key is still missing, and tapping WRITE CODING or
 * REVERT TO BACKUP there without both keys loaded comes straight here instead of just sitting disabled.
 */
@Composable
fun SigningToolsScreen(vm: MainViewModel) {
    BackHandler { vm.closeSigningTools() }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        TextButton(onClick = { vm.closeSigningTools() }) { Text("‹ PREPARE HEADUNIT", fontSize = 12.sp) }
        Text("Signing tools", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(
            "Writing a coding bit needs two small keys recovered once from your own PSdZData. Tap LOAD SIGNING " +
                "TOOLS for what that means and where it has to come from.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(
            onClick = { vm.showRecoveryInfo = true },
            enabled = !vm.recoveringKeys,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.recoveringKeys) "LOADING…" else "LOAD SIGNING TOOLS…") }
        if (vm.recoveringKeys) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (vm.recoveryFeedback.isNotEmpty()) {
            Text(
                vm.recoveryFeedback, fontSize = 11.sp,
                color = if (vm.recoveryFeedbackError) Red else muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        StatusRow("ncd_key.json (signs the coding)", if (vm.hasNcdKey) "Imported" else null, found = vm.hasNcdKey)
        Spacer(Modifier.height(8.dp))
        StatusRow("l3_key.json (unlocks writing)", if (vm.hasL3Key) "Imported" else null, found = vm.hasL3Key)
        Text(
            "Already have ncd_key.json / l3_key.json from a PC run instead? Import them directly:",
            fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
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
        Spacer(Modifier.height(32.dp))
    }

    if (vm.showRecoveryInfo) {
        AlertDialog(
            onDismissRequest = { vm.showRecoveryInfo = false },
            title = { Text("These have to be your own files") },
            text = {
                Column {
                    Text(
                        "The two coding keys come out of BMW's own dealer-level diagnostic software (ISTA/PSdZ) — " +
                            "not something this app ships, stores, or can fetch for you, and for legal reasons never " +
                            "will. You have to supply your own copy of it.",
                    )
                    Text(
                        "Specifically: the small security/ files (sec_security.lic, sec_keyreference.xml, " +
                            "sec_ncdkeys.xml, sec_auth_l3.xml) AND resource.jar, which holds the two BMW logo " +
                            "images the recovery also needs. In a real PSdZ install these usually live in two " +
                            "different folders (resource.jar is often under PSdZ/bin/psdz/, separate from " +
                            "security/) — pick (or gather everything into) one folder that has both somewhere " +
                            "underneath it, or the logo images on their own won't be found.",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Packaging doesn't matter beyond that — loose, zipped, nested inside another zip, still " +
                            "inside resource.jar — point this at a folder containing all of it, in any of those " +
                            "shapes, and it'll be found.",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Nothing you pick ever leaves this phone.",
                        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.showRecoveryInfo = false
                    folderPicker.launch(null)
                }) { Text("CHOOSE FOLDER") }
            },
            dismissButton = { TextButton(onClick = { vm.showRecoveryInfo = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Research step for the SSH-unlock feature, not the feature itself: reads the car's live
 * `starter.cfg` over the SSH window that's already open, and lets the owner export it to send along.
 * Needs no new unlock - [vm.detect.ssh] is already true wherever this is shown.
 */
@Composable
private fun StarterCfgResearchSection(vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) vm.exportStarterCfgTo(uri) }

    Spacer(Modifier.height(14.dp))
    Text(
        "Research: read the car's current starter.cfg (building the SSH-unlock feature) and export it to send along.",
        fontSize = 11.sp, color = muted,
    )
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { vm.readStarterCfg() }, enabled = !vm.scBusy) {
            Text(if (vm.scBusy) "READING…" else "READ STARTER.CFG")
        }
        if (vm.scHasStock) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { saver.launch("starter" + (vm.scTag?.let { "_$it" } ?: "") + ".cfg") }) {
                Text("EXPORT…")
            }
        }
    }
    if (vm.scFeedback.isNotEmpty()) {
        Text(
            vm.scFeedback, fontSize = 11.sp, color = if (vm.scError) Red else muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun WriteCodingSection(vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Write coding", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text(
        "Reads a fresh BackUp of the HeadUnit and then You can write whatever is not set from the required " +
            "coding parameters to it.",
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
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (backup.ok) "Backup read: ${backup.vin}, ${backup.mem?.size ?: 0} coding bytes."
                else "Backup read failed (${backup.steps.lastOrNull { !it.ok }?.detail ?: "see log"}).",
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (backup.ok) Green else Red, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.removeCodingBackup() }, modifier = Modifier.padding(start = 2.dp)) {
                Text("✕", color = Red, fontWeight = FontWeight.Bold)
            }
        }
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
        Box(Modifier.padding(top = 10.dp).fillMaxWidth()) {
            Button(
                onClick = { vm.onWriteCodingTapped() },
                enabled = vm.canWriteCoding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.forceWriteArmed) "WRITE AGAIN" else "WRITE CODING…") }
            // A disabled Button swallows touches, so nothing would ever reach a 5-tap gesture on it -
            // this invisible overlay sits on top only while it's disabled, just to catch those taps.
            if (!vm.canWriteCoding && !vm.codingBusy) {
                Box(Modifier.matchParentSize().clickable(onClick = { vm.onWriteButtonMultiTap() }))
            }
        }
        OutlinedButton(
            onClick = { vm.onRevertTapped() },
            enabled = vm.codingBackup?.mem != null && !vm.codingBusy,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        ) { Text("REVERT TO BACKUP") }
    }

    if (vm.codingWriteLog != null) {
        TextButton(onClick = { vm.showCodingWriteLog = true }, modifier = Modifier.padding(top = 6.dp)) { Text("SHOW LOG") }
    }

    val securityAccessFailed = vm.codingWriteLog?.any { !it.ok && it.name.startsWith("SecurityAccess") } == true
    if (securityAccessFailed) {
        Text(
            "SecurityAccess was rejected above. This vehicle's firmware may unlock a coding write at a different " +
                "level than this app assumes — read-only check, never sends a key, writes nothing:",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        OutlinedButton(
            onClick = { vm.probeSecurityAccessLevels() },
            enabled = vm.detect.zgw != null && !vm.probingSecurityAccess,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.probingSecurityAccess) "PROBING…" else "PROBE SECURITYACCESS LEVELS") }
        if (vm.probingSecurityAccess) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }

    vm.securityAccessProbeLog?.let { log ->
        Column(Modifier.padding(top = 12.dp)) {
            Text("Level probe:", fontSize = 12.sp, color = muted)
            for (step in log) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (step.ok) Green else Red))
                    Text("${step.name}: ${step.detail}", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The small "(i)" next to CODING PASSED / CODINGS NOT SET: opens the per-bit breakdown. */
@Composable
private fun InfoBadge(onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("i", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** The write/revert log behind SHOW LOG, as a small dark console - the same idea as the install log's and the Reset window's. */
@Composable
internal fun WriteLogConsole(log: List<com.nbtaad.editor.net.WriteStep>, maxHeight: Dp) {
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(Color(0xFF0E120E), RoundedCornerShape(6.dp))
            .verticalScroll(scroll)
            .padding(8.dp),
    ) {
        if (log.isEmpty()) {
            Text("Nothing written yet.", fontSize = 12.sp, color = Color(0xFFD0D4D0))
        }
        for (step in log) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (step.ok) Color(0xFF7CD68A) else Color(0xFFFF8A80)))
                Text(
                    "${step.name}: ${step.detail}",
                    fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD0D4D0), modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** The (i) popup's content: every coding bit, flat (no group headers), console-style - green if it passed, red if not. */
@Composable
private fun CodingCheckConsole(results: List<com.nbtaad.editor.net.CodingResult>, maxHeight: Dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(Color(0xFF0E120E), RoundedCornerShape(6.dp))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        if (results.isEmpty()) {
            Text("Nothing read yet.", fontSize = 12.sp, color = Color(0xFFD0D4D0))
        }
        for (r in results) {
            val passed = r.status == com.nbtaad.editor.net.CodingStatus.SET
            val label = if (passed) {
                r.bit.token
            } else {
                val note = when (r.status) {
                    com.nbtaad.editor.net.CodingStatus.NOT_SET -> "NOT SET (reads ${r.rawValue})"
                    com.nbtaad.editor.net.CodingStatus.UNCERTAIN -> "reads ${r.rawValue}"
                    com.nbtaad.editor.net.CodingStatus.UNREADABLE -> "couldn't read"
                    com.nbtaad.editor.net.CodingStatus.SET -> "" // unreachable: passed covers SET
                }
                "${r.bit.token}: $note"
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(
                    if (passed) "✓ " else "✗ ",
                    fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                    color = if (passed) Color(0xFF7CD68A) else Color(0xFFFF8A80),
                )
                Text(
                    label,
                    fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD0D4D0),
                )
            }
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
