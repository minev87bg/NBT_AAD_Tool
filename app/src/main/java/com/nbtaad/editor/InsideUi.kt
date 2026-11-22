package com.nbtaad.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.AddOns
import com.nbtaad.editor.addon.BuildSummary
import com.nbtaad.editor.addon.EntryKind
import com.nbtaad.editor.addon.Match
import com.nbtaad.editor.addon.Recognition
import com.nbtaad.editor.experimental.AiLogic
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.experimental.Markdown

private fun sizeText(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n bytes"
}

/** The colour and the mark of a verdict. */
private fun toneOf(level: Recognition.Level): Color = when (level) {
    Recognition.Level.NO -> Red
    Recognition.Level.READY -> Green
    Recognition.Level.NOT_HERE -> Amber
}

private fun markOf(level: Recognition.Level): String = when (level) {
    Recognition.Level.NO -> "✗ "
    Recognition.Level.READY -> "✓ "
    Recognition.Level.NOT_HERE -> "! "
}

/**
 * What LOOK INSIDE found, kept short: the answer ("Installable now" or why not), the match as a big percentage with
 * the build's files as a checklist, then the AI check. Everything else (what the app checked, every file, notes) is
 * behind the (i). Nothing here installs anything; TRY TO INSTALL only hands the files to the Install page.
 */
@Composable
internal fun InsideDialog(exp: ExperimentalController, ins: ExperimentalController.Inside) {
    val rec = ins.recognition
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tone = toneOf(rec.level)
    var info by remember { mutableStateOf(false) }
    if (info) InsideDetails(ins) { info = false }
    AlertDialog(
        onDismissRequest = { exp.closeInside() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ins.link.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                InfoDot { info = true }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(markOf(rec.level) + rec.headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tone)
                val b = rec.build
                if (b != null) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.percent}%", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = tone)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("${b.have.size} of ${b.total} files of a build", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(b.version?.let { "version $it" } ?: "no version found in the daemon", fontSize = 12.sp, color = muted)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { b.percent / 100f },
                        color = tone,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    BuildChecklist(b)
                } else {
                    Text(rec.answer, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
                // why not, in red, when the answer is no
                if (b != null && !rec.installable) Text(rec.answer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Red, modifier = Modifier.padding(top = 8.dp))
                for (p in rec.problems.drop(if (b != null && !rec.installable) 1 else 0).take(2)) {
                    Text("• $p", fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 3.dp))
                }
                if (rec.installable) {
                    Text(
                        "Unofficial file: the version on the head unit stays as a way back.",
                        fontSize = 11.sp,
                        color = muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (exp.insideKept.isNotEmpty()) Text(exp.insideKept, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 4.dp))

                Text(
                    if (rec.installable) "Ask Claude about this file (optional)" else "Can the app apply this?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "Sends the post, the file list, the scripts and the README of a linked project to Claude with your own key. " +
                        "Claude only describes; nothing it says is run.",
                    fontSize = 11.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
                OutlinedButton(onClick = { exp.askAboutInside() }, enabled = !exp.insideAiBusy) {
                    Text(
                        when {
                            exp.insideAiBusy -> "READING…"
                            !exp.aiKeySet -> "SET AI KEY"
                            exp.insideAi != null -> "ASK AGAIN"
                            else -> "ASK AI"
                        },
                        fontSize = 12.sp,
                    )
                }
                if (exp.insideAiBusy) {
                    Text("Claude is reading the post and the files…", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (exp.insideAiMessage.isNotEmpty()) Text(exp.insideAiMessage, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 4.dp))
                exp.insideAi?.let { text ->
                    val blocks = remember(text) { Markdown.parse(text) }
                    Column(Modifier.padding(top = 8.dp)) {
                        MarkdownView(blocks)
                        Text(
                            "Written by Claude (${AiLogic.MODEL}) from the text above. It can make mistakes and cannot tell " +
                                "whether a file is safe.",
                            fontSize = 11.sp,
                            color = muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (rec.installable) {
                TextButton(onClick = { exp.installInside() }) { Text("TRY TO INSTALL") }
            }
        },
        dismissButton = {
            Row {
                if (rec.installable) {
                    TextButton(onClick = { exp.keepInside() }, enabled = !exp.insideSaved) {
                        Text(if (exp.insideSaved) "ALREADY SAVED" else "SAVE FOR LATER")
                    }
                }
                TextButton(onClick = { exp.closeInside() }) { Text("CLOSE") }
            }
        },
    )
}

/** The 7 files of a build as a checklist: the daemon's two programs, then the plugin's five files as a count. */
@Composable
private fun BuildChecklist(b: BuildSummary) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daemon", fontSize = 12.sp, color = muted, modifier = Modifier.width(60.dp))
            for (n in b.daemonFiles) {
                val ok = n in b.have
                Text(
                    (if (ok) "✓ " else "✗ ") + n + (if (ok) "" else " missing"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ok) Green else Red,
                    modifier = Modifier.padding(end = 14.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("Plugin", fontSize = 12.sp, color = muted, modifier = Modifier.width(60.dp))
            val have = b.pluginHave.size
            val all = have + b.pluginMissing.size
            if (b.pluginMissing.isEmpty()) {
                Text("✓ $have of $all files", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green)
            } else {
                Text(
                    "$have of $all files, missing " + b.pluginMissing.joinToString(", "),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (have == 0) muted else Amber,
                )
            }
        }
    }
}

/** Everything the window leaves out: what the app made of the file, what it checked, every file in it, notes. */
@Composable
private fun InsideDetails(ins: ExperimentalController.Inside, onClose: () -> Unit) {
    val rec = ins.recognition
    val s = ins.survey
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showNested by remember { mutableStateOf(false) }
    // the plugin's own zips hold many small files (pictures, texts): they are one line until asked for
    val nested = remember(s) { s.entries.count { AddOns.inBuildZip(it) } }
    val listed = remember(s, showNested) { if (showNested) s.entries else s.entries.filterNot { AddOns.inBuildZip(it) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Details", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(rec.verdict, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = toneOf(rec.level))
                Text(rec.title, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                if (rec.installable) {
                    Text(
                        "Not an official release: nobody has checked these files, and \"installable\" does not promise that it works. " +
                            "TRY TO INSTALL only hands them to the Install page, where you confirm again. The version now on the " +
                            "head unit is kept there as a way back.",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                for (f in rec.facts) Text("• $f", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                for (p in rec.problems) Text("• $p", fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 3.dp))
                if (rec.match == Match.UNKNOWN || rec.match == Match.KNOWN_ADDON) {
                    Text(
                        "The app can check what a file is, not whether it is safe.",
                        fontSize = 11.sp,
                        color = Amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "Files (${s.entries.size - nested}" + (if (nested > 0) " and $nested inside the plugin's zips" else "") + ")",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                for (e in listed.take(60)) {
                    Column(Modifier.padding(top = 5.dp)) {
                        Text(e.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${sizeText(e.size)} · ${e.kind.label}" + (if (e.note.isNotEmpty()) " (${e.note})" else ""),
                            fontSize = 10.sp,
                            // what can run is marked, so it is not overlooked in a long list
                            color = if (e.kind == EntryKind.PROGRAM || e.kind == EntryKind.SCRIPT) Amber else muted,
                        )
                    }
                }
                if (listed.size > 60) Text("… and ${listed.size - 60} more", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 5.dp))
                if (nested > 0) {
                    TextButton(onClick = { showNested = !showNested }) {
                        Text(if (showNested) "HIDE THE FILES INSIDE THE PLUGIN'S ZIPS" else "SHOW THE $nested FILES INSIDE THE PLUGIN'S ZIPS", fontSize = 11.sp)
                    }
                }
                for (n in s.notes) Text(n, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } },
    )
}
