package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================================
// Install Android Auto: one page for both head unit generations.
//
// "Check Platform" (below) reads the head unit's live bootloader id to tell NBT EVO apart from the
// older, original NBT (see net/PlatformCheck.kt); if that can't be read (no gateway, no answer), the
// person picks by hand instead. Either way, once a platform is known, this page shows exactly today's
// InstallScreen or JjAaScreen beneath the check, unchanged - inst/jjAa and their controllers are not
// touched by any of this, only routed to.
// =========================================================================

@Composable
fun InstallHubScreen(vm: MainViewModel) {
    when (vm.platform) {
        Platform.EVO -> InstallTheme { InstallHubContent(vm) }
        Platform.ORIGINAL_NBT -> JjAaTheme { InstallHubContent(vm) }
        null -> InstallHubContent(vm)
    }
}

@Composable
private fun InstallHubContent(vm: MainViewModel) {
    BackHandler { vm.backToHub() }
    Column(Modifier.fillMaxSize()) {
        PlatformCard(vm)
        Box(Modifier.weight(1f)) {
            when (vm.platform) {
                Platform.EVO -> InstallScreen(vm, embedded = true)
                Platform.ORIGINAL_NBT -> JjAaScreen(vm, embedded = true)
                null -> Unit // nothing to show yet; the card above is doing the work
            }
        }
    }
}

@Composable
private fun PlatformCard(vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Text("Install Android Auto", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Works for both NBT EVO (2015.09+) and the older, original NBT (2012/07-2015/09) - from different " +
                "projects, since the two head units are not compatible with each other.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                when (vm.platform) {
                    Platform.EVO -> PlatformKnownRow(vm, "NBT EVO")
                    Platform.ORIGINAL_NBT -> PlatformKnownRow(vm, "The original NBT (not EVO)")
                    null -> PlatformUnknownBody(vm)
                }
            }
        }
    }
}

@Composable
private fun PlatformKnownRow(vm: MainViewModel, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Platform", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = { vm.resetPlatform() }) { Text("Not this?", fontSize = 12.sp) }
    }
}

@Composable
private fun PlatformUnknownBody(vm: MainViewModel) {
    Text("Current status", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    if (vm.platformMessage.isNotEmpty()) {
        Text(
            vm.platformMessage,
            fontSize = 12.sp,
            color = if (vm.platformError) Red else Color.Unspecified,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (vm.platformBusy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
    Row(Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { vm.checkPlatform() }, enabled = !vm.platformBusy) {
            Text("CHECK PLATFORM", fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
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
        ) { Text("NBT EVO", fontSize = 12.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { vm.choosePlatform(Platform.ORIGINAL_NBT) },
            enabled = !vm.platformBusy,
            modifier = Modifier.weight(1f),
        ) { Text("Original NBT", fontSize = 12.sp) }
    }
}
