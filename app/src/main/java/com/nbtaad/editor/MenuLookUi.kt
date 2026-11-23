package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.Fit
import com.nbtaad.editor.addon.IconSpec
import com.nbtaad.editor.addon.MenuLook
import com.nbtaad.editor.addon.PictureMaker
import com.nbtaad.editor.install.MenuLookController
import com.nbtaad.editor.install.MenuLookScripts

/**
 * The page of the menu look: the label of the CarLife entry in the iDrive menu ("Android Auto") and the person's own
 * pictures for it. CHECK changes nothing; APPLY keeps a copy of every file it replaces; RESTORE puts them all back.
 */
@Composable
fun MenuLookScreen(vm: MainViewModel) {
    val m = vm.menuLook
    val inst = vm.inst
    val s = m.shown
    BackHandler { vm.closeMenuLook() }
    var info by remember { mutableStateOf(false) }
    if (info) MenuLookInfo { info = false }
    if (m.askApply) ApplyAsk(m)
    if (m.askRestore) RestoreAsk(m)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val noCar = inst.noUnit
    val canAct = !m.working && !noCar && vm.watch.green
    var pending by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rel = pending
        pending = null
        if (uri != null && rel != null) m.choosePicture(rel, uri)
    }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) m.makeFromPicture(uri)
    }
    var aiAsk by remember { mutableStateOf(false) }
    if (aiAsk) AiDrawAsk(m) { aiAsk = false }
    if (vm.exp.aiKeyDialog) {
        AiKeyDialog(
            vm.exp,
            title = "AI key",
            what = "DRAW WITH AI sends the words you type (what the icon should show) to Claude, an AI model from Anthropic, " +
                "which draws the icon. That needs your own Anthropic API key. The same key is used for the summaries.",
            cost = "Anthropic bills each drawing to your account; one costs a cent or less, and the console shows your usage.",
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Menu look", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            InfoDot { info = true }
        }
        Text(
            "Makes the CarLife entry of the iDrive menu read \"Android Auto\" (aa-menu-look by deathwalker80).",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
        if (s == null) {
            Text("No menu look file is chosen. Pick the zip on the Add-ons page.", fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            OutlinedButton(onClick = { vm.chooseAddOns() }, modifier = Modifier.padding(top = 12.dp)) { Text("GO TO ADD-ONS") }
            return@Column
        }

        HeadUnitGate(vm, paused = m.working)

        MlBox(Modifier.padding(top = 14.dp)) {
            Text(s.kit.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (s.kit.known) "✓ Identical to the published build" else "! Not identical to a build the app knows",
                fontSize = 12.sp, color = if (s.kit.known) Green else Amber, modifier = Modifier.padding(top = 2.dp),
            )
            Text(s.origin, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
        }

        MlBox(Modifier.padding(top = 10.dp)) {
            Text("On the head unit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val r = m.report
            if (r == null) {
                Text(if (noCar) "No car connected yet." else "Not looked at yet.", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
            } else {
                Text(
                    if (m.brandsFound.isEmpty()) "No menu texts of a known brand (BMW, MINI) were found."
                    else "Menu texts of: " + m.brandsFound.joinToString(", ") { MenuLook.brandName(it) },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (m.brandsFound.isEmpty()) Red else Color.Unspecified,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    if (m.installed) "The menu look is applied" + (m.installedBrand?.let { " for ${MenuLook.brandName(it)}" } ?: "") + " (${m.installedFiles} file(s) kept as a way back)."
                    else "Not applied.",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                )
                if (r.state["oldkit"] == "yes") Text("An older menu kit is installed: remove it first.", fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 3.dp))
            }
            OutlinedButton(onClick = { m.look() }, enabled = canAct, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (m.busy == MenuLookScripts.Action.LOOK) "LOOKING…" else "LOOK AT THE HEAD UNIT", fontSize = 12.sp)
            }
        }
        if (noCar) {
            Text(
                "No car found. The buttons work once the phone is on the car's WiFi. The file stays on this phone until then.",
                fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 10.dp),
            )
        }

        // brand
        Text("1  Which menu", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        if (m.brandsFound.isEmpty()) {
            Text("Press LOOK AT THE HEAD UNIT: the app finds out which menu texts the head unit has.", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            for (b in m.brandsFound) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m.brand == b, onClick = { m.chooseBrand(b) }, enabled = !m.working)
                    Text(MenuLook.brandName(b), fontSize = 13.sp)
                }
            }
            Text("A normal BMW (and a Supra with the BMW menu) uses \"BMW\".", fontSize = 11.sp, color = muted)
        }

        // label
        Text("2  The label", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        OutlinedTextField(
            value = m.label, onValueChange = { m.label = it },
            singleLine = true, enabled = !m.working, isError = m.labelProblem != null,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text("Replaces \"n. t. a. (ASIA)\" / \"CarLife\"") },
        )
        m.labelProblem?.let { Text(it, fontSize = 11.sp, color = Red) }

        // pictures
        val slots = m.brand?.let { MenuLook.slots(it) } ?: emptyList()
        if (slots.isNotEmpty()) {
            Text("3  Pictures (optional)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
            Text(
                "Make the picture of every place from ONE picture: the app scales it to the exact size of each place. " +
                    "Or let the AI draw an icon. None is included (the Android Auto logo is Google's). With none, only the label changes.",
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { sourcePicker.launch(arrayOf("image/*")) },
                    enabled = !m.working && !m.making && !m.aiBusy, modifier = Modifier.weight(1f),
                ) { Text(if (m.making) "READING…" else "MAKE FROM A PICTURE", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { if (vm.exp.aiKeySet) aiAsk = true else vm.exp.openKeyDialog() },
                    enabled = !m.working && !m.making && !m.aiBusy, modifier = Modifier.weight(1f),
                ) { Text(if (m.aiBusy) "DRAWING…" else "DRAW WITH AI", fontSize = 12.sp) }
            }
            if (m.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (m.aiMessage.isNotEmpty() && !aiAsk) Text(m.aiMessage, fontSize = 12.sp, color = if (m.aiBusy) muted else Red, modifier = Modifier.padding(top = 4.dp))
            m.source?.let { src -> SourceBox(m, src) }
            for (slot in slots) {
                val c = m.pictures[slot.rel]
                MlBox(Modifier.padding(top = 8.dp)) {
                    Row {
                        if (c != null && c.problem == null) {
                            PicturePreview(c.bytes)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(slot.what, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("PNG, ${slot.sizeText}, ${MenuLook.colorName(slot.colorType)}", fontSize = 11.sp, color = muted)
                            if (c != null) {
                                Text(
                                    if (c.problem == null) (if (c.made) "✓ made from ${c.name}" else "✓ ${c.name}") else "✗ ${c.name}: ${c.problem}",
                                    fontSize = 12.sp, color = if (c.problem == null) Green else Red, modifier = Modifier.padding(top = 3.dp),
                                )
                                c.note?.let { Text(it, fontSize = 11.sp, color = Amber) }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = { pending = slot.rel; picker.launch(arrayOf("image/png")) }, enabled = !m.working) {
                            Text(if (c == null) "CHOOSE PNG" else "CHOOSE ANOTHER", fontSize = 12.sp)
                        }
                        if (c != null) TextButton(onClick = { m.clearPicture(slot.rel) }, enabled = !m.working) { Text("CLEAR", fontSize = 12.sp) }
                    }
                }
            }
            if (m.brand?.startsWith("bmw") == true) {
                Text(
                    when {
                        m.factoryBusy -> "Reading the head unit's own tile picture and clearing the Baidu icon from it…"
                        m.factoryTiles.isNotEmpty() ->
                            "The three tall BMW tile pictures are 72×378. Your icon is put over the head unit's own tile picture, " +
                                "with the Baidu CarLife icon cleared out of it first (read once from it), so the usual background " +
                                "stays but Baidu's icon does not. How that comes out cannot be fully seen here. If it looks off, " +
                                "RESTORE brings the originals back."
                        else ->
                            "The three tall BMW tile pictures are 72×378: without the head unit's own picture behind it (press " +
                                "LOOK AT THE HEAD UNIT with the car connected), a square icon sits in the middle with empty space " +
                                "around it. How the iDrive shows that cannot be seen here. If it looks off, RESTORE brings the originals back."
                    },
                    fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 6.dp),
                )
                if (m.factoryMessage.isNotEmpty()) Text(m.factoryMessage, fontSize = 11.sp, color = Amber, modifier = Modifier.padding(top = 2.dp))
            }
            if (m.pictureMessage.isNotEmpty()) Text(m.pictureMessage, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp))
        }

        // steps
        Text("4  Check, then apply", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        Text(
            "CHECK makes the new texts in the head unit's memory and checks the pictures, and changes nothing. It takes a while on the head unit.",
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = { m.check() }, enabled = canAct && m.canApply, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Text(if (m.busy == MenuLookScripts.Action.CHECK) "CHECKING…" else "CHECK (CHANGES NOTHING)")
        }
        OutlinedButton(onClick = { m.askToApply() }, enabled = canAct && m.canApply, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
            Text(if (m.busy == MenuLookScripts.Action.APPLY) "APPLYING…" else "APPLY…")
        }

        Text("5  Undo", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
        Text("Puts every replaced file back from the copy kept on the head unit.", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { m.askToRestore() }, enabled = canAct, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Text(if (m.busy == MenuLookScripts.Action.RESTORE) "RESTORING…" else "RESTORE THE ORIGINALS…", color = if (canAct) Red else Color.Unspecified)
        }

        if (m.busy != null) {
            Text(m.step, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        m.result?.let { MlResult(vm, m, it) }
        Spacer(Modifier.height(32.dp))
    }
}

/** The picture all places are made from, with how it goes into them. */
@Composable
private fun SourceBox(m: MenuLookController, src: MenuLookController.Source) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val thumb = remember(src) { bitmapOf(PictureMaker.render(src.pixels, 96, 96, Fit.WHOLE)) }
    MlBox(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box96(thumb)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (src.fromAi) "Drawn by the AI" else src.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${src.pixels.width}×${src.pixels.height}", fontSize = 11.sp, color = muted)
                if (src.small) Text("A small picture: it will look soft in the bigger places.", fontSize = 11.sp, color = Amber)
            }
            TextButton(onClick = { m.forgetSource() }, enabled = !m.working) { Text("REMOVE", fontSize = 12.sp) }
        }
        Text("How it goes into a place", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        for ((f, text) in listOf(Fit.WHOLE to "All of it, with empty margins", Fit.FILL to "Fill the place, cut off the edges")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = m.fit == f, onClick = { m.chooseFit(f) }, enabled = !m.working)
                Text(text, fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = m.plainBackground, onCheckedChange = { m.choosePlainBackground(it) }, enabled = !m.working)
            Text("Make a plain background transparent", fontSize = 13.sp)
        }
        if (m.backgroundNote.isNotEmpty()) Text(m.backgroundNote, fontSize = 11.sp, color = Amber)
        if (m.brand == null) Text("The pictures are made as soon as you choose the menu (step 1).", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun bitmapOf(p: com.nbtaad.editor.addon.Pixels): ImageBitmap =
    android.graphics.Bitmap.createBitmap(p.argb, p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

/** A picture on a dark ground, so what is transparent can be told from what is not. */
@Composable
private fun Box96(img: ImageBitmap) {
    androidx.compose.foundation.layout.Box(Modifier.size(64.dp).background(Color(0xFF30303A), RoundedCornerShape(8.dp)).padding(4.dp)) {
        Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** The PNG that will be sent, as it is (shown on a dark ground). */
@Composable
private fun PicturePreview(bytes: ByteArray) {
    val img = remember(bytes) {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    if (img != null) {
        androidx.compose.foundation.layout.Box(Modifier.size(width = 64.dp, height = 96.dp).background(Color(0xFF30303A), RoundedCornerShape(8.dp)).padding(4.dp)) {
            Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** What the AI should draw. */
@Composable
private fun AiDrawAsk(m: MenuLookController, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var wasBusy by remember { mutableStateOf(false) }
    // the dialog closes by itself when a drawing came
    androidx.compose.runtime.LaunchedEffect(m.aiBusy) {
        if (m.aiBusy) wasBusy = true else if (wasBusy && m.aiMessage.isEmpty()) close()
    }
    AlertDialog(
        onDismissRequest = { if (!m.aiBusy) close() },
        title = { Text("Draw with AI") },
        text = {
            Column {
                Text(
                    "Say what the icon should show. Claude draws a new, simple icon from your words; it does not copy a " +
                        "company's logo. The pictures of all places are made from it, and you can try again.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) }, enabled = !m.aiBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("For example: a white phone on a blue rounded square") },
                    minLines = 2, maxLines = 4,
                )
                if (m.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (m.aiMessage.isNotEmpty()) {
                    Text(m.aiMessage, fontSize = 12.sp, color = if (m.aiBusy) MaterialTheme.colorScheme.onSurfaceVariant else Red, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { m.drawWithAi(text) }, enabled = !m.aiBusy && IconSpec.descriptionProblem(text) == null) { Text("DRAW") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !m.aiBusy) { Text("CLOSE") } },
    )
}

@Composable
private fun MlBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun MlResult(vm: MainViewModel, m: MenuLookController, r: MenuLookController.Result) {
    ResultCard(
        vm, r.ok, r.headline, r.lines, r.hint, r.log, m.showLog, { m.showLog = it },
        restartAdvised = r.ok && (r.action == MenuLookScripts.Action.APPLY || r.action == MenuLookScripts.Action.RESTORE),
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun ApplyAsk(m: MenuLookController) {
    val kit = m.shown?.kit ?: return
    val count = m.pictures.filterValues { it.problem == null }.size
    AlertDialog(
        onDismissRequest = { m.askApply = false },
        title = { Text("Change the menu?", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${kit.title}: label \"${m.label}\" for ${m.brand?.let { MenuLook.brandName(it) } ?: "?"}" + (if (count > 0) ", $count picture(s)" else "") + ".", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "This replaces the head unit's menu text file (text.db) and the chosen pictures. The factory copy of each file is kept first in /opt/aa/menu-look-backup, and RESTORE puts them all back.",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                if (!m.checkedOk) Text("CHECK has not been run since the last change. Running it first is recommended.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
                if (!kit.known) Text("This aa-look is not identical to a build the app knows.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
                Text("Restart the head unit afterwards.", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { m.apply() }) { Text("APPLY") } },
        dismissButton = { TextButton(onClick = { m.askApply = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun RestoreAsk(m: MenuLookController) {
    AlertDialog(
        onDismissRequest = { m.askRestore = false },
        title = { Text("Put the originals back?", fontSize = 17.sp) },
        text = { Text("Every file the menu look replaced is put back from the kept copy, and the kept copies are removed. Restart the head unit afterwards.", fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = { m.restore() }) { Text("RESTORE", color = Red) } },
        dismissButton = { TextButton(onClick = { m.askRestore = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun MenuLookInfo(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("About the menu look", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val lines = listOf(
                    "aa-menu-look is an optional extra of the Android Auto tile. It makes the CarLife entry of the iDrive menu read \"Android Auto\" (or a label you choose) in every language, and can replace its pictures with your own.",
                    "Made by deathwalker80 on the work of RRudkinp (the text.db format, issue #55). Licence: PolyForm Noncommercial 1.0.0.",
                    "The label is changed on the head unit itself by the kit's aa-look program, which the app copies into the head unit's memory and runs there. Nothing is installed and no PC tools are needed.",
                    "Unlike the tile, this changes head-unit files: the menu text file (text.db) and the pictures you choose. Before a file is replaced the app keeps the factory copy in /opt/aa/menu-look-backup (the same folder and layout as the author's scripts), and RESTORE puts every one back byte for byte.",
                    "Pictures need exactly the size of the original. MAKE FROM A PICTURE does that for you: pick one picture (or let the AI draw an icon from your words) and the app scales it to every place, then shows each result. None is included: the Android Auto logo is a Google trademark and the tile backgrounds are BMW or MINI artwork.",
                    "The app does not run the zip's own scripts: it uses aa-look and does the same steps itself, with checks of its own.",
                )
                for (l in lines) Text("• $l", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } },
    )
}
