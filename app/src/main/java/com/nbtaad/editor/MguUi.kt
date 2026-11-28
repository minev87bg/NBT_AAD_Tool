package com.nbtaad.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.mgu.McController

// =========================================================================
// The MGU (iDrive 7 / iDrive 8) hub - phase one of this app's support for the newer, Android-based head unit
// hardware (see McController.kt). Reached from the same Detect Platform front door as the other two hubs, once
// the live BTLD read comes back as MGU's own id.
//
// This screen never enables root SSH itself: that is a separate, one-time step the person runs on their own
// computer (not something this app ports - see the "Root SSH" note below), whose result is a private key this
// screen only ever consumes. What this screen DOES do is prove that key works and show what the Store's own
// deploy tool (if installed) reports; browsing and installing apps from the catalog is the next phase.
// =========================================================================

@Composable
fun HubMguScreen(vm: MainViewModel) {
    val mc = vm.mc
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text("iDrive 7 / iDrive 8 (MGU)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Early support, built on robertnisipeanu/mgu-store" +
                (vm.detect.hu?.let { ", head unit $it" } ?: ""),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { vm.switchPlatform() }, contentPadding = PaddingValues(0.dp)) {
            Text("Not this? Switch platform", fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Root SSH", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This app doesn't enable root SSH on the unit, and won't - that's a one-time, higher-risk step " +
                        "done on a computer with a separate tool. Once it's done, that tool leaves you a private " +
                        "key for this car; paste it below and this app uses it to log in, the same way the Store's " +
                        "own installer does.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Head unit status", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = mc.keyPem,
                    onValueChange = { mc.keyPem = it },
                    label = { Text("Root private key (PEM)") },
                    placeholder = { Text("-----BEGIN ... PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = mc.hostOverride,
                    onValueChange = { mc.hostOverride = it },
                    label = { Text("Head unit address (or address:port)") },
                    placeholder = { Text("auto (hotspot or ENET)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { mc.saveKey() }, modifier = Modifier.weight(1f)) {
                        Text(if (mc.keySaved) "SAVED ✓" else "SAVE")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { mc.checkStatus() },
                        enabled = !mc.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("CHECK STATUS") }
                }
                if (mc.message.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(mc.message, fontSize = 12.sp, color = if (mc.error) Red else MaterialTheme.colorScheme.onSurface)
                }
                if (mc.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Browsing and installing apps from the Store's catalog is next, once this connects cleanly on real hardware.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
    }
}
