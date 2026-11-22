package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.contribute.ContributeController
import com.nbtaad.editor.contribute.HeadLog
import com.nbtaad.editor.contribute.LogFinder
import com.nbtaad.editor.contribute.LogSessions
import com.nbtaad.editor.contribute.PackedLogs
import com.nbtaad.editor.experimental.Topic

// =========================================================================
// Contribute: send the head unit's logs to the project
// =========================================================================

@Composable
fun ContributeScreen(vm: MainViewModel) {
    val c = vm.contribute
    BackHandler { if (c.choosing) c.choosing = false else vm.backFromContribute() }
    var info by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item(key = "__head") {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(52.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Contribute", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box {
                        InfoDot { info = true }
                        if (info) {
                            InfoBalloon(
                                "What is sent",
                                "Nothing is sent by the app. It makes one zip on this phone from the files you tick, and " +
                                    "you attach it to the issue yourself, on GitHub.\n\n" +
                                    "Before a log goes into the zip, the WiFi password of the car's hotspot, the VIN and the " +
                                    "car's GPS positions (the daemon writes all three into its log) are hidden. The zip " +
                                    "also holds about.txt: this app's version, the phone, and what was hidden.\n\n" +
                                    "The daemon's logs are in the head unit's memory: they are gone after a reboot, so " +
                                    "pack them before you restart the car. Reading is slow (about 30 KB/s).",
                                mono = false,
                            ) { info = false }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pack the head unit's logs into one zip and attach it to an issue.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "__logs") { LogsCard(c, vm) }
        item(key = "__target") { TargetCard(c) }
        item(key = "__pack") { PackCard(c) }
        item(key = "__end") { Spacer(Modifier.height(32.dp)) }
    }
    if (c.choosing) TopicPicker(vm)
}

@Composable
internal fun LogsCard(c: ContributeController, vm: MainViewModel? = null) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Logs on the head unit", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val logs = c.logs
            when {
                c.looking -> {
                    Text(c.lookMessage.ifEmpty { "Looking at the head unit…" }, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                c.lookError -> Text(c.lookMessage, fontSize = 12.sp, color = Red)
                logs == null -> Text("Not looked yet.", fontSize = 12.sp, color = soft)
                logs.isEmpty() -> Text(
                    "No log files found. The daemon writes them when Android Auto has run since the head unit started " +
                        "(they are in memory), or to a USB stick when \"Save logs to USB\" is ticked in its menu.",
                    fontSize = 12.sp,
                    color = soft,
                )
                else -> for (l in logs) LogRow(c, l)
            }
            // the phone was pulled away from the car's WiFi: the way back is next to the message that says so
            if (vm != null && c.lookError && vm.dongle != null) {
                OutlinedButton(onClick = { vm.openConnectAgain() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("CONNECT AGAIN", fontSize = 12.sp)
                }
            }
            // the other things that help, next to the logs
            CheckRow("nbt-aad.env (your settings)", c.withEnv) { c.setEnv(it) }
            CheckRow("This app's own log", c.withAppLog) { c.setAppLog(it) }
            Row {
                TextButton(onClick = { c.look() }, enabled = !c.working) { Text("LOOK AGAIN", fontSize = 12.sp) }
                TextButton(onClick = { c.askClear = true }, enabled = !c.working && !logs.isNullOrEmpty()) {
                    Text("CLEAR LOGS", fontSize = 12.sp)
                }
            }
            // what clearing said, under the button that did it
            if (c.clearing || c.clearMessage.isNotEmpty()) {
                Text(c.clearMessage, fontSize = 12.sp, color = if (c.clearError) Red else if (c.clearing) soft else Green)
                if (c.clearing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (!c.clearing && !c.clearError && vm != null) {
                    Text(
                        "A restart of the head unit is recommended: the daemon then starts new logs.",
                        fontSize = 11.sp,
                        color = soft,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ResetButton(vm, Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if (c.askClear) ClearLogsDialog(c)
    if (c.sessionLog != null) SessionsDialog(c)
}

@Composable
private fun ClearLogsDialog(c: ContributeController) {
    val files = c.logs.orEmpty()
    AlertDialog(
        onDismissRequest = { c.askClear = false },
        title = { Text("Clear the logs?", fontSize = 18.sp) },
        text = {
            Column {
                Text("This deletes these files on the head unit, in its memory and on the USB stick:", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                for (l in files) {
                    Text(
                        l.name + (if (l.place.isNotEmpty()) "  ·  " + l.place else ""),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "They cannot be brought back. Pack the ones you still need first. While Android Auto runs, restart the " +
                        "head unit afterwards, so that it starts new logs.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { c.clearLogs() }) { Text("DELETE") } },
        dismissButton = { TextButton(onClick = { c.askClear = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun LogRow(c: ContributeController, l: HeadLog) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    // the sessions that go into the zip instead of the whole log, when some were chosen
    val sessions = c.sessionsOf(l)
    val sizeText = if (sessions.isNotEmpty() && sessions.all { it.size > 0 }) LogFinder.sizeText(sessions.sumOf { it.size }) else
        if (sessions.isEmpty()) LogFinder.sizeText(l.size) else ""
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !c.packing) { c.toggle(l.path) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = l.path in c.picked, onCheckedChange = { c.toggle(l.path) }, enabled = !c.packing)
        Column(Modifier.weight(1f)) {
            Text(l.name, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    if (sessions.isNotEmpty()) LogSessions.numbersText(sessions.map { it.number }) else "",
                    sizeText,
                    l.place,
                ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                fontSize = 11.sp,
                color = soft,
            )
        }
        // the log on the stick holds many runs: the person may take some of them
        if (l.hasSessions) {
            TextButton(
                onClick = { c.openSessions(l) },
                enabled = !c.working,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("CHOOSE SESSION", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun TargetCard(c: ContributeController) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Send to", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(c.targetText(), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                OutlinedButton(onClick = { c.choosing = true }, enabled = !c.packing) {
                    Text("CHOOSE ISSUE OR PR", fontSize = 12.sp)
                }
                if (c.target != null) {
                    TextButton(onClick = { c.chooseTarget(null) }, enabled = !c.packing) { Text("NEW ISSUE", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
internal fun PackCard(c: ContributeController) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current
    val packed = c.packed
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) c.saveTo(u)
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            when {
                c.packing -> {
                    val total = c.packTotal
                    Text(
                        if (c.packLabel.isEmpty()) c.packMessage else "Reading ${c.packLabel}…",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (c.packLabel.isNotEmpty()) {
                        Text(
                            "${LogFinder.sizeText(c.packDone)}" + if (total > 0) " of ${LogFinder.sizeText(total)}" else "",
                            fontSize = 12.sp,
                            color = soft,
                        )
                    }
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (c.packDone.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    Text(
                        "The head unit is slow: a log of 1 MB takes about half a minute.",
                        fontSize = 11.sp,
                        color = soft,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = { c.cancelPack() }) { Text("STOP", fontSize = 12.sp) }
                }
                packed != null -> PackedBlock(c, packed, save = { name -> saver.launch(name) }, openPage = { uri.openUri(c.pageUrl()) })
                else -> {
                    if (c.packMessage.isNotEmpty()) {
                        Text(c.packMessage, fontSize = 12.sp, color = if (c.packError) Red else soft)
                        Spacer(Modifier.height(4.dp))
                    }
                    Button(onClick = { c.pack() }, enabled = !c.working && !c.nothingChosen(), modifier = Modifier.fillMaxWidth()) {
                        Text("PACK THE LOGS")
                    }
                    Text(
                        "Reads the ticked files, hides the WiFi password, the VIN and the GPS positions, and makes the zip.",
                        fontSize = 11.sp,
                        color = soft,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The zip is ready: what is in it, SAVE, and then the page to attach it on (the button sits under its instruction). */
@Composable
private fun PackedBlock(c: ContributeController, p: PackedLogs, save: (String) -> Unit, openPage: () -> Unit) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Ready: ${p.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        LogFinder.sizeText(p.bytes.size.toLong()) + "  ·  " + p.files.joinToString(", "),
        fontSize = 12.sp,
        color = soft,
    )
    Text(
        "Hidden: ${p.passwords} WiFi password${if (p.passwords == 1) "" else "s"}, ${p.vins} VIN${if (p.vins == 1) "" else "s"}, " +
            "${p.positions} GPS position${if (p.positions == 1) "" else "s"}.",
        fontSize = 12.sp,
        color = Green,
        modifier = Modifier.padding(top = 2.dp),
    )
    for (s in p.skipped) Text("Left out: $s", fontSize = 12.sp, color = Amber)

    Spacer(Modifier.height(8.dp))
    if (c.saved && c.saveNote.isNotEmpty()) {
        // it is on the phone already (Downloads, or the place the person chose)
        Text(c.saveNote, fontSize = 12.sp, color = Green)
    } else {
        Text("1. Save the zip on this phone", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { save(p.name) }) { Text("SAVE THE ZIP", fontSize = 12.sp) }
        if (c.saveNote.isNotEmpty()) Text(c.saveNote, fontSize = 12.sp, color = Red)
    }
    if (c.saved) TextButton(onClick = { save(p.name) }) { Text("SAVE A COPY SOMEWHERE ELSE", fontSize = 11.sp) }

    Spacer(Modifier.height(4.dp))
    Text(if (c.saved) "Attach it on the page" else "2. Attach it on the page", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "The page opens at the comment box (${c.targetText()}). Tap the paperclip (or the attach link), pick the zip " +
            "from the Downloads folder, then press Comment. The browser asks you to sign in to GitHub if you are not.",
        fontSize = 12.sp,
        color = soft,
    )
    Button(onClick = openPage, enabled = c.saved, modifier = Modifier.padding(top = 4.dp)) {
        Text("OPEN ON GITHUB", fontSize = 12.sp)
    }
    if (!c.saved) Text("Save the zip first.", fontSize = 11.sp, color = soft)
}

/** The open issues and pull requests of the project (already loaded for the Experimental page), to pick one. */
@Composable
private fun TopicPicker(vm: MainViewModel) {
    val c = vm.contribute
    val exp = vm.exp
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { exp.open() }
    val q = query.trim().removePrefix("#").lowercase()
    val shown = (exp.topics ?: emptyList()).filter { it.open }.filter {
        q.isEmpty() || it.title.lowercase().contains(q) || it.number.toString().startsWith(q) || it.author.lowercase().contains(q)
    }
    AlertDialog(
        onDismissRequest = { c.choosing = false },
        title = { Text("Send to…", fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search by title or number", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    exp.topics == null && exp.loading -> {
                        Text("Loading from GitHub…", fontSize = 12.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    exp.topics == null -> Text(
                        exp.message.ifEmpty { "The list could not be loaded." },
                        fontSize = 12.sp,
                        color = Red,
                    )
                    shown.isEmpty() -> Text("Nothing matches.", fontSize = 12.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.number }) { t -> TopicChoice(t) { c.chooseTarget(t) } }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                TextButton(onClick = { c.chooseTarget(null) }) { Text("NEW ISSUE") }
                TextButton(onClick = { c.choosing = false }) { Text("CLOSE") }
            }
        },
    )
}

@Composable
private fun TopicChoice(t: Topic, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(
            (if (t.isPr) "PR #" else "#") + "${t.number}  ${t.title}",
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${t.author}  ·  updated ${TimeText.full(t.updated)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
