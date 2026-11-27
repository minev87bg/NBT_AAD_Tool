package com.nbtaad.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================================
// Platform: the app's own front door. Nothing else needs to know which head unit generation this is
// until this page has decided - NBT EVO opens today's full hub (Screen.HUB, unchanged: Prepare headunit,
// Install, Change a setting, Add-ons and the community page are all EVO/NBT-AAD-specific), the older,
// original NBT opens a small hub of its own (Screen.HUB_NBT1) with just its one relevant feature,
// installing Android Auto / CarPlay.
//
// CHECK PLATFORM reads the head unit's live bootloader id over UDS (see net/PlatformCheck.kt); if that
// can't be read (no gateway, no answer), the person picks by hand instead.
// =========================================================================

@Composable
fun PlatformScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        if (vm.platform == null && !vm.platformBusy) vm.checkPlatform()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text("NBT AAD Tool", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Works for both NBT EVO (2015.09+) and the older, original NBT (2012/07-2015/09) - from different " +
                "projects, since the two head units are not compatible with each other.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Check platform", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                if (vm.platformMessage.isNotEmpty()) {
                    Text(
                        vm.platformMessage,
                        fontSize = 12.sp,
                        color = if (vm.platformError) Red else Color.Unspecified,
                    )
                }
                if (vm.platformBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.checkPlatform() },
                    enabled = !vm.platformBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("CHECK PLATFORM") }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Or pick it yourself, if you already know which you have:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    OutlinedButton(
                        onClick = { vm.choosePlatform(Platform.EVO) },
                        enabled = !vm.platformBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("NBT EVO", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.choosePlatform(Platform.ORIGINAL_NBT) },
                        enabled = !vm.platformBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Original NBT", fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// =========================================================================
// The original (non-Evo) NBT's own small hub: one feature, Install Android Auto / CarPlay.
// =========================================================================

@Composable
fun HubNbt1Screen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text("NBT Android Auto", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "The original, non-Evo NBT (2012/07-2015/09)" +
                (vm.detect.hu?.let { ", head unit $it" } ?: ""),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.switchPlatform() }, contentPadding = PaddingValues(0.dp)) {
            Text("Not this? Switch platform", fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        NbtOneTile(
            title = "Install Android Auto / CarPlay",
            subtitle = "JayJay3071's package",
            info = "Gets its releases from GitHub (or a file already on the phone) and installs over SSH, the same " +
                "way its own installer does.",
        ) { vm.chooseJjAa() }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NbtOneTile(title: String, subtitle: String, info: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
            Spacer(Modifier.height(8.dp))
            Text(info, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        }
    }
}
