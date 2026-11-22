package com.nbtaad.editor

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.TileLogic
import com.nbtaad.editor.install.TileController
import com.nbtaad.editor.install.TileScripts
import kotlinx.coroutines.delay

/**
 * The page of the Android Auto tile: what the head unit has of it, TEST from the head unit's memory, INSTALL AT BOOT,
 * REMOVE. Each step is one tap and says what it changes; the install keeps the head unit's original start file and
 * REMOVE puts it back.
 */
@Composable
fun TileScreen(vm: MainViewModel) {
    val tile = vm.tile
    val inst = vm.inst
    val s = tile.shown
    BackHandler { vm.closeTile() }
    var info by remember { mutableStateOf(false) }
    if (info) TileInfo { info = false }
    if (tile.askInstall) InstallAsk(tile)
    if (tile.askRemove) RemoveAsk(tile)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val noCar = inst.noUnit
    val canAct = !tile.working && !noCar && vm.watch.green

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Android Auto tile", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            InfoDot { info = true }
        }
        Text(
            "Puts an Android Auto entry in the iDrive main menu (nbt-aa-tile by deathwalker80).",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
        if (s == null) {
            Text("No tile file is chosen. Pick the zip on the Add-ons page.", fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            OutlinedButton(onClick = { vm.chooseAddOns() }, modifier = Modifier.padding(top = 12.dp)) { Text("GO TO ADD-ONS") }
            return@Column
        }

        HeadUnitGate(vm, paused = tile.working)

        // the file
        Box2(Modifier.padding(top = 14.dp)) {
            Text(s.kit.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (s.kit.known) "✓ Identical to the published build" else "! Not identical to a build the app knows",
                fontSize = 12.sp, color = if (s.kit.known) Green else Amber, modifier = Modifier.padding(top = 2.dp),
            )
            Text(s.origin, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
        }

        // the head unit
        Box2(Modifier.padding(top = 10.dp)) {
            Text("On the head unit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val r = tile.report
            if (r == null) {
                Text(
                    if (noCar) "No car connected yet." else "Not looked at yet.",
                    fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(tile.statusHeadline(r), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                val facts = listOfNotNull(
                    if (r.tileRunning) "The tile program is running now." else null,
                    if (r.aadRunning) "Android Auto (nbt-aad) is running." else "Android Auto (nbt-aad) is not running now.",
                    if (r.disabled) "Switched off by the tile's own safety (DISABLED)." else null,
                    if (r.crashed) "It crashed earlier (CRASHED)." else null,
                    r.last?.let { "Launcher: $it" },
                )
                for (f in facts) Text("• $f", fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                if (r.carlifeMissing) {
                    Text(
                        "This head unit software has no CarLife entry (common/widget/CarLife.pbw), so the tile cannot appear here. " +
                            "The author found this on a BMW F30 with NBTevo_L17193I.",
                        fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            OutlinedButton(onClick = { tile.look() }, enabled = canAct, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (tile.busy == TileScripts.Action.STATUS) "LOOKING…" else "LOOK AT THE HEAD UNIT", fontSize = 12.sp)
            }
            val v = inst.unit?.installedVersion
            when {
                tile.daemonTooOld -> Text(
                    "nbt-aad $v is older than the tile needs (${TileLogic.NEEDS_DAEMON}). Update it on the Install page first.",
                    fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp),
                )
                tile.daemonUnknown && !noCar -> Text(
                    "The nbt-aad version could not be read. The tile needs ${TileLogic.NEEDS_DAEMON} or newer.",
                    fontSize = 11.sp, color = Amber, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (noCar) {
            Text(
                "No car found. The buttons work once the phone is on the car's WiFi. The file stays on this phone until then.",
                fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 10.dp),
            )
        }

        // 1. test
        Text("1  Test (nothing is installed)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        Text(
            "Runs the tile for ${TileScripts.TEST_SECONDS / 60} minutes from the head unit's memory, then it hides itself. " +
                "Start Android Auto on the phone first, then watch the iDrive main menu.",
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = { tile.test() }, enabled = canAct && !tile.carlifeMissing, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Text(if (tile.busy == TileScripts.Action.TEST) "TEST RUNNING…" else "TEST FOR 2 MINUTES")
        }
        if (tile.busy == TileScripts.Action.TEST) TestRunning(tile)

        // 2. install
        Text("2  Install at boot", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        Text("When should the tile show?", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        for (m in TileScripts.Mode.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = tile.mode == m, onClick = { tile.mode = m }, enabled = !tile.working)
                Text(m.label, fontSize = 13.sp)
            }
        }
        OutlinedButton(onClick = { tile.askToInstall() }, enabled = canAct && !tile.daemonTooOld && !tile.carlifeMissing, modifier = Modifier.padding(top = 4.dp).fillMaxWidth()) {
            Text(if (tile.busy == TileScripts.Action.INSTALL) "INSTALLING…" else "INSTALL AT BOOT…")
        }

        // 3. remove
        Text("3  Remove", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        Text("Takes the program away and puts the head unit's original start file back.", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { tile.askToRemove() }, enabled = canAct, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Text(if (tile.busy == TileScripts.Action.REMOVE) "REMOVING…" else "REMOVE THE TILE…", color = if (canAct) Red else androidx.compose.ui.graphics.Color.Unspecified)
        }

        if (tile.busy != null && tile.busy != TileScripts.Action.TEST) {
            Text(tile.step, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        tile.result?.let { ResultBox(vm, tile, it) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Box2(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun TestRunning(tile: TileController) {
    val left by produceState(0L, tile.testEndsAt) {
        while (tile.testEndsAt > 0L) {
            value = ((tile.testEndsAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text("$left s left. Now look at the iDrive:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("• Is there a tile in the main MENU? It is labelled \"Baidu CarLife\": the tile reuses that entry.", fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Text("• Press it: Android Auto must come back.", fontSize = 12.sp)
        if (tile.mode == TileScripts.Mode.FOLLOW) {
            Text("• Switch off the phone's WiFi: the tile must disappear; switch on: it comes back.", fontSize = 12.sp)
        }
        Text("Do not close the app. The tile goes away by itself at the end.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        val total = TileScripts.TEST_SECONDS.toFloat()
        LinearProgressIndicator(progress = { (1f - left / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun ResultBox(vm: MainViewModel, tile: TileController, r: TileController.Result) {
    ResultCard(
        vm, r.ok, r.headline, r.lines, r.hint, r.log, tile.showLog, { tile.showLog = it },
        restartAdvised = r.ok && (r.action == TileScripts.Action.INSTALL || r.action == TileScripts.Action.REMOVE),
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun InstallAsk(tile: TileController) {
    val kit = tile.shown?.kit ?: return
    AlertDialog(
        onDismissRequest = { tile.askInstall = false },
        title = { Text("Install the tile at boot?", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(kit.title + ", " + tile.mode.label.lowercase() + ".", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "This writes to the head unit's flash: the program and its launcher go to /opt/aa-tile, and one start line " +
                        "is added at the top of /opt/conn/bin/start_webserver.sh. The original of that file is kept next to them " +
                        "and REMOVE puts it back.",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Text("The tile has a boot guard: after a crash or three restarts in a row it switches itself off.", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (!tile.testedOk) Text("It has not been tested in this session. Testing first is recommended.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
                if (!kit.known) Text("These files are not identical to a build the app knows.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
                Text("Restart the head unit afterwards.", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { tile.install() }) { Text("INSTALL") } },
        dismissButton = { TextButton(onClick = { tile.askInstall = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun RemoveAsk(tile: TileController) {
    AlertDialog(
        onDismissRequest = { tile.askRemove = false },
        title = { Text("Remove the tile?", fontSize = 17.sp) },
        text = {
            Text(
                "The program and its launcher are deleted, and the head unit's original start_webserver.sh is put back. " +
                    "Restart the head unit afterwards.",
                fontSize = 13.sp,
            )
        },
        confirmButton = { TextButton(onClick = { tile.remove() }) { Text("REMOVE", color = Red) } },
        dismissButton = { TextButton(onClick = { tile.askRemove = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun TileInfo(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("About the tile", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val lines = listOf(
                    "nbt-aa-tile is a small separate program (aa-tile) that runs next to nbt-aad and shows an Android Auto entry in the iDrive main menu. Pressing it brings Android Auto back.",
                    "Made by deathwalker80 on the work of RRudkinp (the mechanism), Sq00ky/nanaisu, Keukenrol123522 and dn00. Licence: PolyForm Noncommercial 1.0.0.",
                    "It needs nbt-aad ${TileLogic.NEEDS_DAEMON} or newer.",
                    "The author published build 5 as not yet run in a car. Test first.",
                    "The test copies the program into the head unit's memory and runs it for ${TileScripts.TEST_SECONDS} seconds. Nothing is written to the flash.",
                    "The install writes /opt/aa-tile/aa-tile and aa-tile-launcher.sh, keeps the original start_webserver.sh as /opt/aa-tile/start_webserver.sh.original and adds two lines at the top of start_webserver.sh (nothing else in it changes). If the system partition cannot be written, nothing is changed.",
                    "Quick stop without removing: an empty file NO-AA-TILE on a USB stick.",
                    "The app does not run the zip's own scripts: it uses three of its files and does the same steps itself, with checks of its own.",
                )
                for (l in lines) Text("• $l", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } },
    )
}
