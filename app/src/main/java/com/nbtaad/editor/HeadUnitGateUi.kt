package com.nbtaad.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.net.HeadUnitLight
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live look at the head unit's SSH, on the pages that cannot do anything without it. It looks every few seconds while
 * the page is open (not while a step runs on the head unit: [paused]), and says in a box that cannot be missed what is
 * wrong when SSH does not answer. The page keeps its buttons off until the light is green ([MainViewModel.watch]).
 */
@Composable
fun HeadUnitGate(vm: MainViewModel, paused: Boolean) {
    val w = vm.watch
    val inst = vm.inst
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (true) {
            if (w.look()) vm.onSshGreen()
            delay(if (w.green) 6000L else 3000L)
        }
    }
    val l = w.light
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
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
            Text(
                "Looking again every few seconds" + (if (w.lastTry > 0) " (last look ${clock(w.lastTry)}, look ${w.tries})" else "") +
                    ". The buttons below come on when this turns green.",
                fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
