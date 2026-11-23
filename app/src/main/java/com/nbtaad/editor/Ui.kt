package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nbtaad.editor.data.ThemeMode
import com.nbtaad.editor.env.ConfigParser
import com.nbtaad.editor.env.CustomOption
import com.nbtaad.editor.env.EnvCheck
import com.nbtaad.editor.env.Opt

internal val Green = Color(0xFF2E7D32)
internal val Red = Color(0xFFC62828)
internal val Amber = Color(0xFFC77700)

/** The blue of the "Change a setting" tile's icon on the choice page: the accent of the settings page. */
internal val SettingsBlue = Color(0xFF5B93F0)

/**
 * The settings page's buttons, headings, links and separators take the blue of its tile's icon instead of the app's
 * green, so it is recognised as the page of that tile. On the light theme a deeper blue keeps the text readable.
 */
@Composable
internal fun SettingsTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) {
        base.copy(
            primary = SettingsBlue, onPrimary = Color(0xFF0D1117),
            primaryContainer = Color(0xFF1B2F4A), onPrimaryContainer = Color(0xFFCFE5FF),
            secondaryContainer = Color(0xFF1B2F4A), onSecondaryContainer = Color(0xFFCFE5FF),
            surfaceTint = SettingsBlue,
        )
    } else {
        base.copy(
            primary = Color(0xFF2F63C0), onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E6FF), onPrimaryContainer = Color(0xFF0D2A57),
            secondaryContainer = Color(0xFFD9E6FF), onSecondaryContainer = Color(0xFF0D2A57),
            surfaceTint = Color(0xFF2F63C0),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The accent of the Add-ons page and every page under it: a soft violet that is easy on the eye on the dark theme. */
internal val AddOnViolet = Color(0xFFB9A3F0)

/** Like [SettingsTheme], for the Add-ons page and the pages that open from it (the tile, the menu look). */
@Composable
internal fun AddOnsTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) {
        base.copy(
            primary = AddOnViolet, onPrimary = Color(0xFF1A1330),
            primaryContainer = Color(0xFF3A2F63), onPrimaryContainer = Color(0xFFE6DEFF),
            secondaryContainer = Color(0xFF3A2F63), onSecondaryContainer = Color(0xFFE6DEFF),
            surfaceTint = AddOnViolet,
        )
    } else {
        base.copy(
            primary = Color(0xFF6B4FBB), onPrimary = Color.White,
            primaryContainer = Color(0xFFE9E2FF), onPrimaryContainer = Color(0xFF2A1B5C),
            secondaryContainer = Color(0xFFE9E2FF), onSecondaryContainer = Color(0xFF2A1B5C),
            surfaceTint = Color(0xFF6B4FBB),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.HUB -> HubScreen(vm)
                Screen.OPTIONS -> SettingsTheme { OptionsScreen(vm) }
                Screen.INSTALL -> InstallTheme { InstallScreen(vm) }
                Screen.EXPERIMENTAL -> ExperimentalScreen(vm)
                Screen.CONTRIBUTE -> ContributeScreen(vm)
                Screen.WIZARD -> WizardScreen(vm)
                Screen.TILE -> AddOnsTheme { TileScreen(vm) }
                Screen.MENULOOK -> AddOnsTheme { MenuLookScreen(vm) }
                Screen.ADDONS -> AddOnsTheme { AddOnsScreen(vm) }
                Screen.PREPARE -> PrepareTheme { PrepareScreen(vm) }
                Screen.SIGNING_TOOLS -> PrepareTheme { SigningToolsScreen(vm) }
            }
            ThemeToggle(vm, Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        if (vm.showLog) AppLogDialog(vm)
        if (vm.askDongle) DongleDialog(vm)
        if (vm.connectDialog) ConnectAgainDialog(vm)
    }
}

/** What the app writes down about its work (for finding out what went wrong): save it to a file and send it along. */
@Composable
internal fun AppLogDialog(vm: MainViewModel) {
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.saveLogTo(uri)
    }
    AlertDialog(
        onDismissRequest = { vm.showLog = false; vm.logNote = "" },
        title = { Text("App log") },
        text = {
            Column {
                Text(
                    "The app writes down what it does (detection, SSH steps, errors) on this phone: ${vm.logSizeText()} " +
                        "now. When something goes wrong, save it to a file and send that file along. The VIN is hidden in it.",
                    fontSize = 13.sp,
                )
                if (vm.logNote.isNotBlank()) {
                    Text(vm.logNote, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { saver.launch(vm.suggestedLogName()) }) { Text("Save to file") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.clearLog() }) { Text("Clear") }
                TextButton(onClick = { vm.showLog = false; vm.logNote = "" }) { Text("Close") }
            }
        },
    )
}

/** Small corner control cycling Theme Auto (follows the phone) -> Theme White -> Theme Dark -> Theme Auto. */
@Composable
private fun ThemeToggle(vm: MainViewModel, modifier: Modifier) {
    val label = when (vm.themeMode) {
        ThemeMode.SYSTEM -> "Theme Auto"
        ThemeMode.LIGHT -> "Theme White"
        ThemeMode.DARK -> "Theme Dark"
    }
    // a soft backing, so text scrolling underneath it does not run into the label
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        TextButton(
            onClick = { vm.cycleTheme() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}

// =========================================================================
// Shared with the Hub page (the app's home page)
// =========================================================================

/** The three dots at the top right of the Hub page: the tools that are not needed every time. The app log has its
 *  own button on that page already, so it is not repeated here. */
@Composable
internal fun MoreMenu(vm: MainViewModel, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 4.dp, height = 18.dp)) {
                val r = 2.dp.toPx()
                for (i in 0..2) drawCircle(dot, radius = r, center = Offset(size.width / 2f, r + i * (size.height - 2 * r) / 2f))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Manual IPs") }, onClick = { open = false; vm.manualDialog = true })
            DropdownMenuItem(text = { Text("Port probe") }, onClick = { open = false; vm.openProbe() })
            DropdownMenuItem(text = { Text("Reset") }, onClick = { open = false; vm.openResetWindow() })
        }
    }
}

/**
 * Reset, from the 3-dot menu: every reset method there is, tried in turn and listed as its own console line (red
 * dot when it did not work) - see [MainViewModel.resetHeadUnitAllMethods]. Unlike the reset buttons elsewhere in the
 * app (which quietly pick whichever one path is reachable), this is the place to see what was actually tried and why
 * a method was skipped or failed.
 */
@Composable
internal fun ResetWindowDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.closeResetWindow() },
        title = { Text("Reset head unit") },
        text = {
            Column {
                Text(
                    "Looks for the vehicle, then resets it: through its gateway (ZGW) when there is one, " +
                        "otherwise the head unit's own shell is asked to restart. Every method is listed below.",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.resetHeadUnitAllMethods() }, enabled = !vm.resetWindowBusy) {
                    Text(if (vm.resetWindowBusy) "RESETTING…" else "RESET HU")
                }
                Spacer(Modifier.height(10.dp))
                WriteLogConsole(vm.resetWindowLog, maxHeight = 260.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeResetWindow() }, enabled = !vm.resetWindowBusy) { Text("Close") }
        },
    )
}

/**
 * The port probe: with Android Auto running, which ports of the head unit answer on the phone's WiFi? It only tries to
 * open connections and closes them again. The result is shown here and written to the app log (Probe).
 */
@Composable
internal fun ProbeDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { if (!vm.probeBusy) vm.closeProbe() },
        title = { Text("Port probe", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.probeText.isEmpty()) {
                    Text(
                        "Start Android Auto first, so the vehicle's screen shows it and the phone is on the vehicle's WiFi. " +
                            "Then press QUICK (ports up to 10000, about a minute) or FULL (every port, a few minutes).\n\n" +
                            "It only looks: it tries to open connections to the head unit and closes them again. " +
                            "It sends nothing and changes nothing. Android Auto may reconnect once while it runs.",
                        fontSize = 12.sp,
                    )
                } else {
                    Text(vm.probeText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (vm.probeBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (vm.probeBusy) {
                TextButton(onClick = { vm.stopProbe() }) { Text("STOP") }
            } else {
                Row {
                    TextButton(onClick = { vm.startProbe(false) }) { Text("QUICK") }
                    TextButton(onClick = { vm.startProbe(true) }) { Text("FULL") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { vm.closeProbe() }) { Text("CLOSE") } },
    )
}

/** Is there a newer version of this app on GitHub? One tap downloads it and hands it to Android's installer. */
@Composable
internal fun UpdateBanner(vm: MainViewModel) {
    val offer = vm.updateOffer
    val installNote = vm.installMessage
    val msg = installNote ?: vm.updateMessage
    val updated = vm.updatedNote
    if (msg.isBlank() && offer == null && updated.isBlank()) return
    val busy = vm.updatePhase == UpdatePhase.DOWNLOADING
    val color = when {
        installNote != null || vm.updatePhase == UpdatePhase.FAILED -> Amber
        vm.updatePhase == UpdatePhase.AVAILABLE || busy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.height(6.dp))
    if (updated.isNotBlank()) {
        // shown for a few minutes after the app was replaced by a newer version: the update worked
        Text(
            updated,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
    Text(
        msg,
        fontSize = 12.sp,
        color = color,
        fontWeight = if (offer != null) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    if (busy) {
        LinearProgressIndicator(
            progress = { vm.updateProgress },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
    if (offer != null && !busy) {
        if (offer.notes.isNotBlank()) {
            Text(
                offer.notes,
                fontSize = 11.sp,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "While it updates, the app closes. Open it again afterwards, the new version is then ready.",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = { vm.startUpdate() }, modifier = Modifier.padding(top = 4.dp)) { Text("Update App") }
    }
}

@Composable
internal fun ManualDialog(vm: MainViewModel) {
    var zgw by remember { mutableStateOf(vm.detect.zgw ?: "") }
    var hu by remember { mutableStateOf(vm.detect.hu ?: "") }
    AlertDialog(
        onDismissRequest = { vm.manualDialog = false },
        title = { Text("Set addresses manually") },
        text = {
            Column {
                Text("Use this if detection fails. The gateway can stay empty.", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = zgw, onValueChange = { zgw = it }, singleLine = true,
                    label = { Text("ZGW address") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hu, onValueChange = { hu = it }, singleLine = true,
                    label = { Text("Head unit address") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.setManual(zgw, hu) }) { Text("Check") } },
        dismissButton = { TextButton(onClick = { vm.manualDialog = false }) { Text("Cancel") } },
    )
}

// =========================================================================
// Screen 2: options
// =========================================================================

@Composable
private fun OptionsScreen(vm: MainViewModel) {
    BackHandler { if (vm.fromWizard) vm.backToWizard() else vm.backToHub() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val visible = vm.visibleRows()
        val changeCount = vm.changes.size
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "__top") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(h * 0.2f))
                        Button(onClick = { vm.onReadClick() }, enabled = !vm.optBusy) { Text("READ OPTIONS") }
                        Text(
                            vm.optFeedback,
                            fontSize = 12.sp,
                            color = if (vm.optError) Red else Color.Unspecified,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        val used = vm.settingCount
                        Text(
                            "Settings switched on: $used of ${EnvCheck.MAX_SETTINGS} the daemon can use",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (used > EnvCheck.MAX_SETTINGS) Red else Color.Unspecified,
                        )
                        // is the list of settings itself up to date? checked again every time this page opens
                        Text(
                            vm.syncMessage,
                            fontSize = 11.sp,
                            color = if (vm.syncOk) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (vm.syncOk) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        CompactField(vm.search, { vm.search = it }, "Search settings", Modifier.fillMaxWidth())
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = vm.onlyActive,
                                onClick = { vm.onlyActive = !vm.onlyActive },
                                label = { Text("Applied or changed only", fontSize = 12.sp) },
                            )
                            Spacer(Modifier.weight(1f))
                            Text("${visible.size} of ${vm.rows.size}", fontSize = 12.sp, color = Color.Gray)
                        }
                        OutlinedButton(onClick = { vm.addDialog = "NBT_" }, modifier = Modifier.fillMaxWidth()) {
                            Text("+ Add an option that is not in the list")
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "__none") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No setting matches.", fontSize = 13.sp)
                            val q = vm.search.trim()
                            if (q.isNotEmpty()) {
                                // a name that is not in the list: offer to add it, the name already filled in
                                TextButton(onClick = { vm.addDialog = if (CustomOption.isName(q)) q else "NBT_" }) {
                                    Text("Add \"$q\" as an option", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                var lastGroup: String? = null
                for (opt in visible) {
                    if (opt.group != lastGroup) {
                        lastGroup = opt.group
                        val g = opt.group
                        item(key = "__group_$g") {
                            GroupHeader(g, visible.count { it.group == g }, mine = g == ConfigParser.GROUP_CUSTOM)
                        }
                    }
                    item(key = opt.key) {
                        // only the options the person added can be taken out of the list again
                        val mine = opt.group == ConfigParser.GROUP_CUSTOM
                        val remove: (() -> Unit)? = if (mine) ({ vm.removeCustom(opt.key) }) else null
                        // the person's own options sit on a tinted ground with a bar down the side, so they cannot be
                        // taken for the settings of the list below
                        val bar = MaterialTheme.colorScheme.primary
                        val ground = if (mine) {
                            Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                .drawBehind { drawRect(bar, size = Size(4.dp.toPx(), size.height)) }
                                .padding(start = 4.dp)
                        } else Modifier
                        Column(Modifier.fillMaxWidth().then(ground)) {
                            OptionRow(
                                opt, vm.values[opt.key], vm.isApplied(opt.key), vm.isChanged(opt.key), vm.orig[opt.key],
                                { vm.undo(opt.key) }, remove,
                            ) { vm.edit(opt.key, it) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Button(
                onClick = { vm.requestSave() },
                enabled = vm.fileKnown && vm.saveDialog == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (vm.fileKnown) 8.dp else 2.dp),
            ) { Text(if (changeCount > 0) "SAVE ($changeCount change${if (changeCount == 1) "" else "s"})" else "SAVE") }
            if (!vm.fileKnown) {
                Text(
                    "SAVE unlocks after READ OPTIONS, so the settings already on the head unit are not overwritten.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // only when this page was opened from the wizard
            if (vm.fromWizard) BackToWizardButton(vm, Modifier.padding(bottom = 8.dp))
        }
    }

    if (vm.confirmRead) {
        val n = vm.changes.size
        AlertDialog(
            onDismissRequest = { vm.confirmRead = false },
            title = { Text("Unsaved changes") },
            text = {
                Text(
                    "$n setting${if (n == 1) " is" else "s are"} changed and not saved. Reading the file from the " +
                        "head unit again will throw those changes away."
                )
            },
            confirmButton = { TextButton(onClick = { vm.readDiscardingChanges() }) { Text("Read anyway") } },
            dismissButton = { TextButton(onClick = { vm.confirmRead = false }) { Text("Keep editing") } },
        )
    }

    vm.review?.let { ReviewDialog(vm, it) }

    vm.saveDialog?.let { SaveDialog(vm, it) }

    vm.addDialog?.let { AddOptionDialog(vm, it) }
}

/** "+ Add an option": a name and, if wanted, its value. The option then lives in "Additional options". */
@Composable
private fun AddOptionDialog(vm: MainViewModel, startName: String) {
    var name by remember { mutableStateOf(startName) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { vm.addDialog = null },
        title = { Text("Add an option") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "For a setting that this list does not have, for example one from a newer or experimental " +
                        "build. It is kept on this phone and listed under \"Additional options\".",
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("NBT_SOMETHING") },
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    singleLine = true,
                    label = { Text("Value (optional)") },
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(it, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = vm.addCustom(name, value)
                if (problem == null) vm.addDialog = null else error = problem
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { vm.addDialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun ReviewDialog(vm: MainViewModel, r: ReviewState) {
    val blocked = r.errors.isNotEmpty()
    AlertDialog(
        onDismissRequest = { vm.review = null },
        title = {
            Text(
                when {
                    blocked -> "This file cannot be uploaded"
                    r.changes.isEmpty() -> "No changes"
                    else -> "Review changes (${r.changes.size})"
                },
                color = if (blocked) Red else Color.Unspecified,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                r.errors.forEach {
                    Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Red, modifier = Modifier.padding(vertical = 4.dp))
                }
                r.warnings.forEach {
                    Text(it, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(vertical = 4.dp))
                }
                if (r.changes.isEmpty() && !blocked) {
                    Text(
                        "No setting differs from the file on the vehicle (comments or other lines may still " +
                            "differ). Upload it anyway?",
                        fontSize = 13.sp,
                    )
                }
                r.changes.forEach { c ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(c.key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("was:  ${c.old ?: "(not set)"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Red)
                        Text("now:  ${c.new ?: "(not set)"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Green)
                    }
                    HorizontalDivider()
                }
                Text(
                    "Settings switched on after this upload: ${r.settingCount} of ${EnvCheck.MAX_SETTINGS}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            if (!blocked) {
                TextButton(onClick = { vm.confirmSave() }) { Text(if (r.changes.isEmpty()) "Upload anyway" else "Upload") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.review = null }) { Text(if (blocked) "Back" else "Cancel") } },
    )
}

@Composable
private fun OptionRow(
    opt: Opt,
    value: String?,
    applied: Boolean,
    changed: Boolean,
    original: String?,
    onUndo: () -> Unit,
    onRemove: (() -> Unit)?,
    onChange: (String?) -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val hasPresets = opt.values.isNotEmpty()
    val isCustomValue = value != null && value !in opt.values
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(8.dp)

    // red = changed in this session, green = applied on the head unit right now
    val mark = when {
        changed -> Modifier.background(Red.copy(alpha = 0.12f), shape).border(1.5.dp, Red, shape)
        applied -> Modifier.background(Green.copy(alpha = 0.10f), shape).border(1.5.dp, Green, shape)
        else -> Modifier
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp).then(mark).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(opt.key, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (opt.summary.isNotBlank()) {
                    Text(opt.summary, fontSize = 11.sp, lineHeight = 14.sp, color = soft, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                opt.default?.let {
                    Text("Default: $it", fontSize = 10.sp, color = soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // an option added by hand: taking it out of the list sits in the heading row, not on a row of its own
            if (onRemove != null) {
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Remove", fontSize = 11.sp)
                }
            }
            Box {
                InfoDot(size = 22.dp) { showInfo = true }
                if (showInfo) InfoBalloon(opt.key, opt.info) { showInfo = false }
            }
        }
        Spacer(Modifier.height(3.dp))

        if (!hasPresets) {
            val ph = when {
                opt.hint != null -> "(not set, e.g. ${opt.hint})"
                opt.default != null -> "(not set, default ${opt.default})"
                else -> "(not set)"
            }
            CompactField(value ?: "", { onChange(it) }, ph, Modifier.fillMaxWidth())
        } else {
            Box {
                OutlinedButton(
                    onClick = { menu = true },
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    val label = when {
                        value == null -> "(not set)"
                        value.isBlank() -> "Custom…"
                        value in opt.values -> opt.choiceText(value)
                        else -> value
                    }
                    Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("▾", fontSize = 12.sp)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("(not set)") },
                        onClick = { onChange(null); menu = false },
                    )
                    opt.values.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(opt.choiceText(v)) },
                            onClick = { onChange(v); menu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Custom…") },
                        onClick = { if (value == null || value in opt.values) onChange(""); menu = false },
                    )
                }
            }
            if (isCustomValue) {
                CompactField(value ?: "", { onChange(it) }, "Type a value", Modifier.fillMaxWidth().padding(top = 3.dp))
            }
        }

        if (opt.closed && !value.isNullOrBlank() && value !in opt.values) {
            Text(
                "\"$value\" is not one of the documented values (${opt.values.joinToString(", ")}). " +
                    "The daemon may ignore it.",
                fontSize = 11.sp,
                color = Amber,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (changed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Changed. Was: ${original?.takeIf { it.isNotBlank() } ?: "(not set)"}",
                    fontSize = 11.sp,
                    color = Red,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Undo", fontSize = 11.sp)
                }
            }
        } else if (applied) {
            Text(
                "Currently applied on the head unit",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Green,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The outlined text field of the stock library, but only as tall as its line needs (the stock one is 56 dp, which is a
 * good part of a row of the settings list), so that more settings are seen at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = source,
        modifier = modifier,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = source,
                placeholder = { Text(placeholder, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

/**
 * The band that opens a section of the settings list. The section of the options the person added is filled solid,
 * the others are pale, and every band has room above it, so the sections do not fade into one another.
 */
@Composable
private fun GroupHeader(name: String, count: Int, mine: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (mine) scheme.primary else scheme.primaryContainer
    val ink = if (mine) scheme.onPrimary else scheme.onPrimaryContainer
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(fill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.weight(1f))
        Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}

@Composable
internal fun InfoDot(size: Dp = 26.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).border(1.dp, Color.Gray, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("i", fontSize = if (size < 26.dp) 12.sp else 14.sp, fontWeight = FontWeight.Bold) }
}

/** The small scrollable balloon under an (i): a grey heading and the text. */
@Composable
internal fun InfoBalloon(heading: String, text: String, mono: Boolean = true, onDismiss: () -> Unit) {
    val offsetY = with(LocalDensity.current) { 30.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
            modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(
                    heading,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(4.dp))
                Text(text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SaveDialog(vm: MainViewModel, d: SaveDialogState) {
    val ok = d.status == SaveStatus.OK
    val scroll = rememberScrollState()
    LaunchedEffect(d.lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val successContainer = if (isDark) Color(0xFF1B3B20) else Color(0xFFE8F5E9)

    AlertDialog(
        onDismissRequest = { if (d.status != SaveStatus.RUNNING) vm.closeSaveDialog() },
        containerColor = if (ok) successContainer else AlertDialogDefaults.containerColor,
        modifier = if (ok) Modifier.border(2.dp, Green, RoundedCornerShape(28.dp)) else Modifier,
        title = {
            Text(
                when (d.status) {
                    SaveStatus.RUNNING -> "Saving…"
                    SaveStatus.OK -> "Saved successfully"
                    SaveStatus.FAIL -> "Save failed"
                },
                color = when (d.status) {
                    SaveStatus.OK -> Green
                    SaveStatus.FAIL -> Red
                    SaveStatus.RUNNING -> Color.Unspecified
                },
            )
        },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(scroll)) {
                d.lines.forEach {
                    Text(
                        it.text,
                        fontSize = 13.sp,
                        color = when (it.kind) {
                            LineKind.INFO -> Color.Unspecified
                            LineKind.OK -> Green
                            LineKind.ERROR -> Red
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (ok) {
                    Spacer(Modifier.height(10.dp))
                    Text("Restarting the head unit is recommended.", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Green)
                    ResetButton(vm, Modifier.padding(top = 8.dp))
                }
                if (d.status == SaveStatus.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        },
        confirmButton = {
            when (d.status) {
                SaveStatus.RUNNING -> {}
                SaveStatus.OK -> TextButton(onClick = { vm.closeSaveDialog() }) { Text("OK") }
                SaveStatus.FAIL -> Row {
                    TextButton(onClick = { vm.checkConnection() }) { Text("Check Connection") }
                    if (d.connectionOk) TextButton(onClick = { vm.save() }) { Text("Retry") }
                }
            }
        },
        dismissButton = {
            if (d.status == SaveStatus.FAIL) {
                TextButton(onClick = { vm.closeSaveDialog() }) { Text("Close") }
            }
        },
    )
}
