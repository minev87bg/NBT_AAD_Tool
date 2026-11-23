package com.nbtaad.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.contribute.ContributeController
import com.nbtaad.editor.contribute.LogFinder
import com.nbtaad.editor.contribute.LogSession
import com.nbtaad.editor.contribute.LogSessions
import java.time.ZoneId

// =========================================================================
// CHOOSE SESSION: which runs of Android Auto of the log on the USB stick go into the zip
// =========================================================================

/**
 * The window of CHOOSE SESSION. The head unit is asked for the session headers of the log (slow: the stick is read from
 * start to end, but only a few lines come back), then the person ticks the runs to send, the newest first. USE THESE packs
 * only those; the whole file is the other way.
 */
@Composable
internal fun SessionsDialog(c: ContributeController) {
    val log = c.sessionLog ?: return
    val list = c.sessionList
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = remember { ZoneId.systemDefault() }
    var showInfo by remember { mutableStateOf(false) }

    // what was chosen before, else the newest run: what most people want
    val start = remember(list, log.path) { c.chosenSessions[log.path] ?: list?.lastOrNull()?.let { setOf(it.number) } ?: emptySet() }
    var picked by remember(start) { mutableStateOf(start) }

    AlertDialog(
        onDismissRequest = { c.closeSessions() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose sessions", fontSize = 18.sp, modifier = Modifier.weight(1f))
                Box {
                    InfoDot { showInfo = true }
                    if (showInfo) {
                        InfoBalloon(
                            "Sessions",
                            "The log on the USB stick keeps every start of Android Auto, one after the other, each with a " +
                                "header line. Here you pick the starts to send, usually the newest one: much less to read " +
                                "from the slow head unit than the whole file.\n\nFinding the headers means reading the stick " +
                                "once, which can take a minute. The list is kept until you look at the head unit again.\n\n" +
                                "The time is the first GPS time the vehicle gave in that run, in your phone's time. A run " +
                                "without GPS has none; then the build and the size tell the runs apart.",
                            mono = false,
                        ) { showInfo = false }
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(log.name + "  ·  " + LogFinder.sizeText(log.size) + "  ·  on the USB stick", fontSize = 12.sp, color = soft)
                Spacer(Modifier.height(8.dp))
                when {
                    c.sessionsLooking -> {
                        Text(c.sessionMessage.ifEmpty { "Looking…" }, fontSize = 13.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text("The stick is read from start to end: about a minute for 5 MB.", fontSize = 11.sp, color = soft, modifier = Modifier.padding(top = 4.dp))
                    }
                    c.sessionError -> {
                        Text(c.sessionMessage, fontSize = 13.sp, color = Red)
                        TextButton(onClick = { c.listSessions(log) }) { Text("TRY AGAIN", fontSize = 12.sp) }
                    }
                    list == null -> Text(c.sessionMessage.ifEmpty { "Not looked yet." }, fontSize = 13.sp, color = soft)
                    list.isEmpty() -> Text(
                        "No session headers in this log. It can only be sent whole.",
                        fontSize = 13.sp,
                    )
                    else -> {
                        for (s in list.reversed()) {
                            SessionRow(s, s.number in picked, zone) {
                                picked = if (s.number in picked) picked - s.number else picked + s.number
                            }
                        }
                    }
                }
                if (!c.sessionsLooking && list != null) {
                    TextButton(onClick = { c.useWholeLog(log) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("or pack the whole file", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { c.useSessions(log, picked) },
                enabled = !c.sessionsLooking && !list.isNullOrEmpty() && picked.isNotEmpty(),
            ) { Text("USE THESE") }
        },
        dismissButton = {
            TextButton(onClick = { c.closeSessions() }) { Text(if (c.sessionsLooking) "STOP" else "CANCEL") }
        },
    )
}

@Composable
private fun SessionRow(s: LogSession, on: Boolean, zone: ZoneId, toggle: () -> Unit) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val time = LogSessions.localTime(s.gps, zone)
    Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = { toggle() })
        Column(Modifier.weight(1f)) {
            Text(
                "Session ${s.number}" + if (s.last) "  ·  newest" else "",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOf(
                    if (s.size > 0) LogFinder.sizeText(s.size) else "",
                    s.build,
                    if (time.isNotEmpty()) "GPS time $time" else "",
                ).filter { it.isNotEmpty() }.joinToString("  ·  ").ifEmpty { "line ${s.line}" },
                fontSize = 11.sp,
                color = soft,
            )
        }
    }
}
