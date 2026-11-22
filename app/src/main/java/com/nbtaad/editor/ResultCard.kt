package com.nbtaad.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What a result box puts on the clipboard: the answer, what the head unit said, and the program's log. */
internal object ResultText {
    fun format(ok: Boolean, headline: String, lines: List<String>, hint: String?, log: String): String = buildString {
        append(if (ok) "OK: " else "FAILED: ").append(headline).append('\n')
        for (l in lines) append("- ").append(l).append('\n')
        if (!hint.isNullOrBlank()) append('\n').append(hint).append('\n')
        if (log.isNotBlank()) append("\nLog:\n").append(log.trimEnd()).append('\n')
    }
}

/**
 * The box a step of the tile page or the menu look page ends with: the answer, what the head unit said, the hint, and
 * COPY for all of it (with the program's log), so that it can be sent as it is. A hint that says the head unit must be
 * restarted is the one thing in the box that must not be missed, so it has a frame and the colour of a warning, and the
 * reset button right under it.
 */
@Composable
internal fun ResultCard(
    vm: MainViewModel,
    ok: Boolean,
    headline: String,
    lines: List<String>,
    hint: String?,
    log: String,
    showLog: Boolean,
    onShowLog: (Boolean) -> Unit,
    restartAdvised: Boolean,
    modifier: Modifier = Modifier,
) {
    val tone = if (ok) Green else Red
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(
        modifier.fillMaxWidth()
            .border(2.dp, tone, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text((if (ok) "✓ " else "✗ ") + headline, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tone)
        for (l in lines.take(12)) Text("• $l", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        if (hint != null && restartAdvised) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth()
                    .background(Amber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .border(2.dp, Amber, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text("RESTART THE HEAD UNIT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
                Text(hint, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.padding(top = 4.dp))
            }
            ResetButton(vm, Modifier.padding(top = 8.dp))
        } else if (hint != null) {
            Text(hint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                clip.setText(AnnotatedString(ResultText.format(ok, headline, lines, hint, log)))
                copied = true
            }) { Text(if (copied) "COPIED ✓" else "COPY", fontSize = 12.sp) }
            if (log.isNotBlank()) {
                TextButton(onClick = { onShowLog(!showLog) }) { Text(if (showLog) "HIDE THE LOG" else "SHOW THE LOG", fontSize = 12.sp) }
            }
        }
        if (log.isNotBlank() && showLog) {
            Text("The program's log. COPY above takes it along; it is in the app log too.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
