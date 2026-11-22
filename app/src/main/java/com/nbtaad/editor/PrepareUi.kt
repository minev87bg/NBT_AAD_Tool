package com.nbtaad.editor

import androidx.activity.compose.BackHandler
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
            "Sq00ky's own project lists these as the head unit coding Android Auto needs; without them the car can " +
                "refuse the phone's link outright. Checking and setting them here, over the same Ethernet gateway and " +
                "without touching the Vehicle Order, is coming next.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        for (item in CODING_REQUIREMENTS) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(item.group, fontSize = 11.sp, color = muted, fontWeight = FontWeight.Medium)
                for (bit in item.bits) {
                    Text("$bit → AKTIV", fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** One coding group from Sq00ky's own coding-requirements.md, and the bits in it that must be AKTIV. */
private class CodingGroup(val group: String, val bits: List<String>)

private val CODING_REQUIREMENTS = listOf(
    CodingGroup("3000 HMI", listOf("REMOTE_HMI", "A4A", "A4A_REMOTE_APS")),
    CodingGroup("3002 AUDIO_TUNER_TRAFFIC", listOf("A4A_ANDROID")),
    CodingGroup("3003 TELEFON_TELEMATIK_ONLINE", listOf("A4A_WIRELESS_ANDROID")),
)

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
