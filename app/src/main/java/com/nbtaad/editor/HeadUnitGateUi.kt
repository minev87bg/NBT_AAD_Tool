package com.nbtaad.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.net.HeadUnitLight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The look at the head unit's SSH, on the pages that cannot do anything without it. Nothing happens on its own: a
 * SCAN THE HEAD UNIT button does one look whenever the person presses it (not while a step runs on the head unit:
 * [paused]), and says in a box that cannot be missed what is wrong when SSH does not answer. The page keeps its
 * buttons off until the light is green ([MainViewModel.watch]).
 */
@Composable
fun HeadUnitGate(vm: MainViewModel, paused: Boolean, onGreen: () -> Unit = { vm.onSshGreen() }) {
    val w = vm.watch
    val inst = vm.inst
    val scope = rememberCoroutineScope()
    fun scan() {
        scope.launch { if (w.look()) onGreen() }
    }
    val l = w.light
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        l.kind == HeadUnitLight.Kind.IDLE -> Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            Text("Not scanned yet.", fontSize = 12.sp, color = muted)
            OutlinedButton(onClick = { scan() }, enabled = !paused, modifier = Modifier.padding(top = 6.dp)) {
                Text("SCAN THE HEAD UNIT", fontSize = 12.sp)
            }
        }

        l.kind == HeadUnitLight.Kind.CHECKING -> Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            Text(HeadUnitLight.headline(l), fontSize = 12.sp, color = muted)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }

        l.green -> Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            Text("● " + HeadUnitLight.headline(l), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green)
            when {
                inst.unitBusy -> Text("Reading the head unit…", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                inst.noUnit && inst.unitError -> Column(Modifier.padding(top = 4.dp)) {
                    Text(
                        "SSH answers, but the app could not read the head unit: ${inst.unitMessage}",
                        fontSize = 12.sp, color = Amber,
                    )
                    TextButton(onClick = { inst.readUnit() }) { Text("TRY AGAIN", fontSize = 12.sp) }
                }
            }
        }

        else -> Column(
            Modifier.padding(top = 14.dp).fillMaxWidth()
                .border(2.dp, Red, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(HeadUnitLight.headline(l), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Red)
            if (l.kind == HeadUnitLight.Kind.PF_BLOCKED) {
                Text(HeadUnitLight.PF_WHY, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                HeadUnitLight.PF_STEPS.forEachIndexed { i, step ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("${i + 1}.", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                        Text(step, fontSize = 13.sp)
                    }
                }
            } else {
                HeadUnitLight.advice(l)?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
            if (w.lastTry > 0) {
                Text(
                    "Last scan ${clock(w.lastTry)} (scan ${w.tries}).",
                    fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
            OutlinedButton(onClick = { scan() }, enabled = !paused, modifier = Modifier.padding(top = 8.dp)) {
                Text("SCAN THE HEAD UNIT", fontSize = 12.sp)
            }
        }
    }
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
