package com.nbtaad.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.EntryKind
import com.nbtaad.editor.addon.Match
import com.nbtaad.editor.experimental.AiLogic
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.experimental.Markdown

private fun sizeText(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n bytes"
}

/**
 * What LOOK INSIDE found: what the app makes of the file, every file in it with what it is, and the AI check. Nothing
 * here installs anything; GET FOR INSTALL is offered only for the files of a build the installer takes.
 */
@Composable
internal fun InsideDialog(exp: ExperimentalController, ins: ExperimentalController.Inside) {
    val rec = ins.recognition
    val s = ins.survey
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tone: Color = when {
        rec.problems.isNotEmpty() -> Red
        rec.installable -> Green
        else -> Amber
    }
    AlertDialog(
        onDismissRequest = { exp.closeInside() },
        title = { Text(ins.link.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(rec.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tone)
                if (exp.insideKept.isNotEmpty()) Text(exp.insideKept, fontSize = 12.sp, color = Green, modifier = Modifier.padding(top = 3.dp))
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
                    "Files (${s.entries.size})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                for (e in s.entries.take(60)) {
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
                if (s.entries.size > 60) Text("… and ${s.entries.size - 60} more", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 5.dp))
                for (n in s.notes) Text(n, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp))

                Text(
                    "Can the app apply this?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "Sends the post, the file list, the text of the scripts and the README of a GitHub project linked in the post " +
                        "to Claude (Anthropic) with your own key. Claude only describes; nothing it says is run.",
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
                TextButton(onClick = { exp.installInside() }) { Text("GET FOR INSTALL") }
            }
        },
        dismissButton = {
            Row {
                if (rec.installable) TextButton(onClick = { exp.keepInside() }) { Text("SAVE FOR LATER") }
                TextButton(onClick = { exp.closeInside() }) { Text("CLOSE") }
            }
        },
    )
}
