package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.contribute.WizardController
import com.nbtaad.editor.contribute.WizardStep
import com.nbtaad.editor.experimental.Topic
import com.nbtaad.editor.net.WifiPanel

// =========================================================================
// PREPARE LOGS: the question (wizard or manual) and the wizard itself
// =========================================================================

/** A button with its (i) next to it: what the button does is explained in the small balloon. */
@Composable
private fun ChoiceRow(label: String, info: String, onClick: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
        Spacer(Modifier.width(10.dp))
        Box {
            InfoDot { showInfo = true }
            if (showInfo) InfoBalloon(label, info, mono = false) { showInfo = false }
        }
    }
}

/** PREPARE LOGS on a page was pressed: a guided way, or the page where the person picks the files himself. */
@Composable
internal fun PrepareChoiceDialog(vm: MainViewModel, t: Topic) {
    AlertDialog(
        onDismissRequest = { vm.wizard.askMode = false },
        title = { Text("Prepare logs") },
        text = {
            Column {
                Text(
                    "For #${t.number}  ${t.title}",
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                ChoiceRow(
                    "USE WIZARD",
                    "Takes you step by step from the test to the logs on this page: which version, which settings, the test, " +
                        "back to the head unit's WiFi, and the logs. The app waits for the head unit by itself.",
                ) { vm.prepareWizard(t) }
                Spacer(Modifier.height(10.dp))
                ChoiceRow(
                    "MANUAL",
                    "One page where you look at the head unit, tick the log files yourself and pack them. For when you " +
                        "know what to send and the head unit is reachable now.",
                ) { vm.prepareManual(t) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.wizard.askMode = false }) { Text("CANCEL") } },
    )
}

@Composable
fun WizardScreen(vm: MainViewModel) {
    val w = vm.wizard
    BackHandler { vm.wizardBack() }
    var info by remember { mutableStateOf(false) }
    ExperimentalTheme {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "__head") {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(52.dp))
                    TextButton(onClick = { vm.wizardBack() }) {
                        Text(if (w.step == WizardStep.VERSION) "‹  Back to the page" else "‹  Back", fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Prepare logs", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Box {
                            InfoDot { info = true }
                            if (info) {
                                InfoBalloon(
                                    "Prepare logs",
                                    "Nothing is sent by the app. It makes one zip from the head unit's logs, with the WiFi " +
                                        "password, the VIN and the GPS positions hidden, saves it in Downloads and opens the page at its " +
                                        "comment box, where you attach it.\n\nThe logs are in the head unit's memory, so " +
                                        "Android Auto must keep running until they are packed.",
                                    mono = false,
                                ) { info = false }
                            }
                        }
                    }
                    w.topic?.let {
                        Text(
                            "#${it.number}  ${it.title}",
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Step ${w.step.number} of ${WizardStep.entries.size}: ${w.step.label}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { w.step.number / WizardStep.entries.size.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            item(key = "__step") {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    when (w.step) {
                        WizardStep.VERSION -> VersionStep(vm)
                        WizardStep.SETTINGS -> SettingsStep(vm)
                        WizardStep.TEST -> TestStep(vm)
                        WizardStep.RETURN -> ReturnStep(vm)
                        WizardStep.LOGS -> LogsStep(vm)
                    }
                }
            }
            item(key = "__end") { Spacer(Modifier.height(32.dp)) }
        }
        if (w.askWhy) WhyDialog(w)
        // CHANGE may find an unfinished file of the last session and asks about it
        if (vm.askDraft) DraftDialog(vm)
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun VersionStep(vm: MainViewModel) {
    StepTitle("Which version will you test on the head unit?")
    Spacer(Modifier.height(14.dp))
    ChoiceRow("CURRENT", "The version that is on the head unit now. Nothing is installed.") { vm.wizardCurrent() }
    Spacer(Modifier.height(10.dp))
    ChoiceRow(
        "DIFFERENT",
        "Opens the Install page, where you pick a release or a file from this phone. BACK TO WIZARD there brings you " +
            "back here.\n\nA build that is shared on the page: press LOOK INSIDE on the page first, then TRY TO INSTALL there, then choose CURRENT here.",
    ) { vm.wizardDifferent() }
}

@Composable
private fun SettingsStep(vm: MainViewModel) {
    val inst = vm.inst
    val u = inst.unit
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    StepTitle("Settings for the test")
    Spacer(Modifier.height(8.dp))
    when {
        inst.unitError -> {
            Text(inst.unitMessage, fontSize = 12.sp, color = Red)
            OutlinedButton(onClick = { inst.readUnit() }, enabled = !inst.unitBusy) { Text("TRY AGAIN", fontSize = 12.sp) }
        }
        u != null -> {
            Text("Version", fontSize = 11.sp, color = soft)
            if (!u.hasDaemon && u.otherNode) {
                Text(
                    u.installedVersion ?: "not known (installed with NBTApps)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (!u.hasDaemon) {
                Text("nbt-aad was not found in ${u.aaDir}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Red)
            } else {
                Text(
                    u.installedVersion ?: "not known (CHECK VERSION on the Install page reads it)",
                    fontSize = if (u.installedVersion != null) 16.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (u.installedVersion != null) FontFamily.Monospace else FontFamily.Default,
                )
            }
            Spacer(Modifier.height(10.dp))
            AppliedSettings(inst, u)
            OutlinedButton(onClick = { vm.wizardChange() }, enabled = !u.otherNode, modifier = Modifier.padding(top = 6.dp)) {
                Text("CHANGE", fontSize = 12.sp)
            }
            if (vm.changes.isNotEmpty()) {
                Text(
                    "${vm.changes.size} change${if (vm.changes.size == 1) " is" else "s are"} not saved yet. Press CHANGE, then SAVE.",
                    fontSize = 12.sp,
                    color = Amber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        inst.unitBusy -> {
            Text(inst.unitMessage.ifEmpty { "Looking at the head unit…" }, fontSize = 12.sp)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        else -> OutlinedButton(onClick = { inst.readUnit() }) { Text("READ THE HEAD UNIT", fontSize = 12.sp) }
    }
    Spacer(Modifier.height(18.dp))
    Button(onClick = { vm.wizard.toTest() }, enabled = !inst.unitBusy, modifier = Modifier.fillMaxWidth()) { Text("NEXT") }
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$n.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TestStep(vm: MainViewModel) {
    StepTitle("Run the test")
    Spacer(Modifier.height(8.dp))
    Numbered(1, "If you changed the version or the settings, restart the head unit: they start with it, and the logs begin clean.")
    ResetButton(vm, Modifier.padding(start = 24.dp, top = 2.dp, bottom = 4.dp))
    Numbered(2, "Join the car's WiFi and start Android Auto on the phone.")
    Numbered(3, "Do what the page asks and try to make the problem happen.")
    Numbered(4, "Then leave Android Auto running. Do not stop it and do not restart anything.")
    Spacer(Modifier.height(16.dp))
    Button(onClick = { vm.wizard.askDone() }, modifier = Modifier.fillMaxWidth()) { Text("I'M DONE TESTING") }
}

/** Why Android Auto must keep running, and a tick to say that it was read. */
@Composable
private fun WhyDialog(w: WizardController) {
    AlertDialog(
        onDismissRequest = { w.cancelWhy() },
        title = { Text("Before you switch the WiFi") },
        text = {
            Column {
                Text(
                    "The logs are kept in the head unit's memory. They are lost when Android Auto, or the head unit, is " +
                        "stopped or restarted.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "So only switch the phone to the dongle's WiFi. Leave Android Auto running and do not restart anything.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { w.understood = !w.understood },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = w.understood, onCheckedChange = { w.understood = it })
                    Text("I understand", fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { w.confirmWhy() }, enabled = w.understood) { Text("CONTINUE") } },
        dismissButton = { TextButton(onClick = { w.cancelWhy() }) { Text("CANCEL") } },
    )
}

@Composable
private fun ReturnStep(vm: MainViewModel) {
    val w = vm.wizard
    val ctx = LocalContext.current
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    var showInfo by remember { mutableStateOf(false) }
    var noList by remember { mutableStateOf(false) }
    val dongle = vm.dongle
    StepTitle("Back to the dongle's WiFi")
    Spacer(Modifier.height(6.dp))
    Text(
        if (dongle != null) {
            "Press CONNECT AGAIN: the app asks you to turn Bluetooth off, then joins ${dongle.ssid}. Android Auto keeps " +
                "running; the app waits for the head unit."
        } else {
            "Switch the phone to the WiFi you use to set up the head unit. Android Auto keeps running; the app waits for the head unit."
        },
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dongle != null) {
            Button(onClick = { vm.openConnectAgain() }, modifier = Modifier.weight(1f)) { Text("CONNECT AGAIN") }
        } else {
            Button(onClick = { noList = !WifiPanel.open(ctx) }, modifier = Modifier.weight(1f)) { Text("CHOOSE WIFI") }
        }
        Spacer(Modifier.width(10.dp))
        Box {
            InfoDot { showInfo = true }
            if (showInfo) {
                InfoBalloon(
                    if (dongle != null) "Connect again" else "Choose WiFi",
                    if (dongle != null) {
                        "Joins ${dongle.ssid} by itself, with the name and password you gave. Bluetooth has to be off first, " +
                            "or Android Auto pulls the phone away again; the window checks that live. The app stays where " +
                            "it is and notices the head unit by itself."
                    } else {
                        "Opens Android's own list of WiFi networks over this app. Tap the dongle's network. The app stays " +
                            "where it is and notices the head unit by itself."
                    },
                    mono = false,
                ) { showInfo = false }
            }
        }
    }
    if (dongle != null) {
        TextButton(onClick = { noList = !WifiPanel.open(ctx) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("or pick it in Android's WiFi list", fontSize = 11.sp)
        }
    }
    if (noList) {
        Text(
            "The phone would not open its WiFi list. Switch the WiFi in the phone's settings and come back here.",
            fontSize = 12.sp,
            color = Red,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("SSH open", fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(16.dp).background(if (w.sshUp == true) Green else Red, CircleShape))
    }
    if (w.gaveUp) {
        Text("The head unit did not answer for 15 minutes.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
        OutlinedButton(onClick = { w.retryWaiting() }) { Text("TRY AGAIN", fontSize = 12.sp) }
    } else {
        Text(
            if (w.sshUp == true) "Connected. On to the logs…" else "Waiting for the head unit…  ${w.waited} s",
            fontSize = 12.sp,
            color = soft,
            modifier = Modifier.padding(top = 6.dp),
        )
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun LogsStep(vm: MainViewModel) {
    val c = vm.contribute
    StepTitle("Pick the logs")
    Text(
        "The ticked files go into one zip, with the WiFi password, the VIN and the GPS positions hidden.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    LogsCard(c, vm)
    PackCard(c)
    TextButton(onClick = { vm.wizardFinish() }, modifier = Modifier.padding(top = 8.dp)) { Text("FINISH", fontSize = 12.sp) }
}
