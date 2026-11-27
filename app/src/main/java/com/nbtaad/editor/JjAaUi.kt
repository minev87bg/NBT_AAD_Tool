package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.install.AssetInfo
import com.nbtaad.editor.install.JjAaController
import com.nbtaad.editor.install.JjPackageView
import com.nbtaad.editor.install.JjShelfItem
import com.nbtaad.editor.install.ReleaseInfo
import com.nbtaad.editor.install.ReleaseLogic
import com.nbtaad.editor.install.RunKind
import com.nbtaad.editor.install.RunState
import com.nbtaad.editor.install.RunStatus
import com.nbtaad.editor.net.JjAa

// =========================================================================
// NBT Android Auto (original NBT) - JayJay3071's installer, in-app
//
// Mirrors "Install / update NBT Android Auto" (InstallUi.kt) part for part - the same shape of status card, shelf,
// choose-what-to-install section, plan/run dialogs - with a dimmed yellow accent instead of green, and adapted to
// JayJay3071's simpler, flat, no-backup protocol (see net/JjAa.kt): no daemon/plugin split, no settings file, and
// no "go back" after an install, because that project's own installer keeps no previous version aside either.
// =========================================================================

/** A dimmed yellow, the way InstallGreen is this page's sibling's own accent. */
internal val JjYellow = Color(0xFFC9A227)

@Composable
internal fun JjAaTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) {
        base.copy(
            primary = JjYellow, onPrimary = Color(0xFF1F1600),
            primaryContainer = Color(0xFF3A2F0A), onPrimaryContainer = Color(0xFFF5E6B3),
            secondaryContainer = Color(0xFF3A2F0A), onSecondaryContainer = Color(0xFFF5E6B3),
            surfaceTint = JjYellow,
        )
    } else {
        base.copy(
            primary = Color(0xFF8A6D1A), onPrimary = Color.White,
            primaryContainer = Color(0xFFF5E6B3), onPrimaryContainer = Color(0xFF3A2F0A),
            secondaryContainer = Color(0xFFF5E6B3), onSecondaryContainer = Color(0xFF3A2F0A),
            surfaceTint = Color(0xFF8A6D1A),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun JjAaScreen(vm: MainViewModel, embedded: Boolean = false) {
    val inst = vm.jjAa
    if (!embedded) BackHandler { vm.backToHub() }
    val list = rememberLazyListState()
    LaunchedEffect(inst.view) { if (inst.view != null) list.animateScrollToItem(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { inst.usePhoneFile(it) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = list) {
        item(key = "__head") {
            Column(Modifier.fillMaxWidth()) {
                if (!embedded) {
                    Spacer(Modifier.height(52.dp))
                    Text("NBT Android Auto (original NBT)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "JayJay3071's Android Auto / CarPlay package, for the OLDER, non-Evo NBT (2012/07-2015/09). This " +
                            "app only reads the project's own release zip and pushes it over SSH, the same way its own " +
                            "installer does - it never bundles or redistributes it.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                TextButton(onClick = { inst.showCredits = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("Credits and licence", fontSize = 12.sp)
                }
                JjUnitCard(inst)
            }
        }
        if (inst.prepBusy || inst.prepMessage.isNotEmpty()) {
            item(key = "__prep") { JjPrepStatus(inst) }
        }
        inst.view?.let { v -> item(key = "__package") { JjPackageCard(inst, v) } }
        if (inst.shelf.isNotEmpty()) item(key = "__shelf") { JjShelfCard(inst, inst.shelf) }

        item(key = "__choose") {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Text("Choose what to install", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !inst.working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("A release zip from this phone…") }
                Text(
                    "Pick the \"...-Python-....zip\" file from a release you already have. The app looks inside it " +
                        "and tells you if it is a package it can read.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                Text("Releases on JayJay3071's page", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (inst.releasesBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                if (inst.releasesMessage.isNotEmpty()) {
                    Text(inst.releasesMessage, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
                if (!inst.releasesBusy && (inst.releases == null || inst.releasesMessage.isNotEmpty())) {
                    TextButton(onClick = { inst.loadReleases() }) { Text("Load the releases again", fontSize = 12.sp) }
                }
            }
        }
        items(inst.releases.orEmpty(), key = { it.tag + it.published }) { r -> JjReleaseRow(inst, r) }

        item(key = "__uninstall") {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp)) {
                Text("Uninstall", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Removes the two folders this package uses, after stopping its processes. JayJay3071's own " +
                        "installer keeps no previous version aside, so there is no \"go back\" for this package - " +
                        "uninstalling and installing again is the only way back.",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { inst.openUninstall() },
                    enabled = !inst.working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Uninstall…", color = if (inst.working) Color.Unspecified else Red) }
            }
        }
    }

    inst.view?.takeIf { inst.askInstall }?.let { JjPlanDialog(inst, it) }
    if (inst.askUninstall) JjUninstallDialog(inst)
    inst.job?.let { JjRunDialog(inst, it) }
    if (inst.showCredits) JjCreditsDialog(inst)
}

// ---------------------------------------------------------------- current status

@Composable
private fun JjUnitCard(inst: JjAaController) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Current status", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val u = inst.unit
            when {
                inst.unitError -> Text(inst.unitMessage, fontSize = 12.sp, color = Red)
                u != null -> JjUnitBody(u)
                inst.unitBusy -> {
                    Text(inst.unitMessage.ifEmpty { "Looking at the head unit…" }, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                else -> Text("Not read yet.", fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = inst.hostOverride,
                onValueChange = { inst.hostOverride = it },
                label = { Text("Head unit address") },
                placeholder = { Text(inst.effectiveHost) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = inst.password,
                onValueChange = { inst.password = it },
                label = { Text("Root password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { inst.readUnit() },
                enabled = !inst.working,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("CHECK", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun JjUnitBody(u: JjAa.JjUnitStatus) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    if (!u.looksInstalled) {
        Text("Not installed: neither folder this package uses was found.", fontSize = 13.sp, color = soft)
        return
    }
    Text(
        u.version ?: "installed, but no VERSION file (an install from before this app?)",
        fontSize = if (u.version != null) 18.sp else 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = if (u.version != null) FontFamily.Monospace else FontFamily.Default,
        color = if (u.version != null) MaterialTheme.colorScheme.onSurface else soft,
    )
    if (u.disabled) {
        Text(
            "Marked disabled on the head unit (an uninstall that did not finish?).",
            fontSize = 12.sp,
            color = Amber,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (u.rtDirExists) JjYellow else Color.Gray)
        Spacer(Modifier.width(8.dp))
        Text("runtime folder (${JjAa.RT_DIR})", fontSize = 11.sp, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (u.pluginDirExists) JjYellow else Color.Gray)
        Spacer(Modifier.width(8.dp))
        Text("plugin folder (${JjAa.PLUGIN_DIR})", fontSize = 11.sp, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- the shelf

private val JjShelfDate = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun jjShelfTime(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(JjShelfDate)

@Composable
private fun JjShelfCard(inst: JjAaController, items: List<JjShelfItem>) {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { open = true }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${items.size} saved file${if (items.size == 1) "" else "s"} on this phone",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Nothing is installed until you choose one", fontSize = 11.sp, color = muted)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 18.sp, color = muted)
        }
    }
    if (open) JjShelfDialog(inst, items) { open = false }
}

@Composable
private fun JjShelfDialog(inst: JjAaController, items: List<JjShelfItem>, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved on this phone") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Nothing is installed until you choose one, press INSTALL and confirm.", fontSize = 11.sp, color = muted)
                for (item in items) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(item.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                item.version.ifBlank { null },
                                if (item.fromRelease) "from the project's Releases page" else "from this phone",
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = muted,
                        )
                        Text(
                            "saved ${jjShelfTime(item.savedAt)}" + (item.installedAt?.let { " · installed ${jjShelfTime(it)}" } ?: ""),
                            fontSize = 11.sp,
                            color = if (item.installedAt != null) JjYellow else muted,
                        )
                        Row {
                            TextButton(onClick = { inst.useShelf(item); onDismiss() }, enabled = !inst.working) { Text("CHOOSE", fontSize = 12.sp) }
                            TextButton(onClick = { inst.removeFromShelf(item) }, enabled = !inst.working) { Text("REMOVE", fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------- preparing a choice

@Composable
private fun JjPrepStatus(inst: JjAaController) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (inst.prepMessage.isNotEmpty()) {
            Text(inst.prepMessage, fontSize = 12.sp, color = if (inst.prepError) Red else Color.Unspecified)
        }
        if (inst.prepBusy) {
            val f = inst.prepFraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

// ---------------------------------------------------------------- releases

private fun jjInfoText(r: ReleaseInfo, asset: AssetInfo?): String = buildString {
    append(r.tag).append('\n')
    if (r.title.isNotBlank() && r.title != r.tag) append(r.title).append('\n')
    append('\n')
    append("Published: ").append(TimeText.full(r.published)).append('\n')
    if (r.prerelease) append("Pre-release: yes\n")
    append(if (asset != null) "File: ${asset.name} (${ReleaseLogic.mb(asset.size)})\n" else "No file this app can read was found in this release.\n")
    if (r.notes.isNotBlank()) {
        append('\n')
        append(r.notes.take(1200))
    }
}

@Composable
private fun JjReleaseRow(inst: JjAaController, r: ReleaseInfo) {
    var info by remember { mutableStateOf(false) }
    val asset = remember(r) { JjAa.payloadAsset(r) }
    if (info) {
        val doc = remember(r) { ExperimentalController.TextDoc(r.tag, r.pageUrl, jjInfoText(r, asset), false) }
        TextFileDialog(doc, showCopy = false) { info = false }
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.tag, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    if (r.prerelease) Text("  pre-release", fontSize = 11.sp, color = Amber, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    TimeText.full(r.published) + (asset?.let { "  ·  ${ReleaseLogic.mb(it.size)}" } ?: ""),
                    fontSize = 12.sp,
                )
                if (r.title.isNotBlank() && r.title != r.tag) {
                    Text(r.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Text(
                    asset?.name ?: "no file this app can read",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            InfoDot { info = true }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { inst.useRelease(r) }, enabled = !inst.working && asset != null) { Text("USE") }
        }
    }
}

// ---------------------------------------------------------------- the chosen package

@Composable
private fun JjPackageCard(inst: JjAaController, v: JjPackageView) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val entries = v.payload.manifest.entries
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Chosen: ${v.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (v.fromProject) {
                Text(
                    "From JayJay3071's Releases page" + if (v.prerelease) " (pre-release: a test build)" else "",
                    fontSize = 12.sp,
                    color = if (v.prerelease) Amber else JjYellow,
                )
            } else {
                Text("From a file on this phone: nobody has checked it.", fontSize = 12.sp, color = Amber)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Version ${v.payload.manifest.version}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = JjYellow,
            )
            Text(
                "${entries.size} file${if (entries.size == 1) "" else "s"}, ${ReleaseLogic.mb(entries.sumOf { it.size })}",
                fontSize = 12.sp,
                color = soft,
            )
            Spacer(Modifier.height(6.dp))
            for (e in entries.take(8)) {
                Text(e.target.substringAfterLast('/'), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = soft)
            }
            if (entries.size > 8) Text("… and ${entries.size - 8} more", fontSize = 11.sp, color = soft)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { inst.askInstall = true }, enabled = !inst.working) { Text("INSTALL ON THE HEAD UNIT") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { inst.clearChoice() }, enabled = !inst.working) { Text("Clear") }
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun JjPlanDialog(inst: JjAaController, v: JjPackageView) {
    var showInfo by remember { mutableStateOf(false) }
    val ready = v.fromProject || inst.trusted
    val details = "Writes ${v.payload.manifest.entries.size} files under ${JjAa.RT_DIR} and ${JjAa.PLUGIN_DIR}, each " +
        "uploaded under a temporary name, read back and checked, then renamed into place - the same atomic write " +
        "the project's own installer does."
    AlertDialog(
        onDismissRequest = { inst.askInstall = false },
        title = { Text("Install on the head unit?") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(
                    (if (v.fromProject) "From JayJay3071's Releases page: " else "From your phone: ") +
                        v.title + " (${v.payload.manifest.version})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Writes ${v.payload.manifest.entries.size} files.", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Box {
                        InfoDot { showInfo = true }
                        if (showInfo) InfoBalloon("What happens", details, mono = false) { showInfo = false }
                    }
                }
                Text(
                    "There is no previous version kept: this package has no \"go back\" the way NBT-AAD's install does.",
                    fontSize = 12.sp,
                    color = Amber,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (!v.fromProject) {
                    Text(
                        "This file is not from JayJay3071's Releases page. The app can check that it is a package " +
                            "for the head unit, but not that it is safe.",
                        fontSize = 12.sp,
                        color = Red,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checked = inst.trusted, onCheckedChange = { inst.trusted = it })
                        Text(
                            "I know where this file comes from and I trust it.",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { inst.install() }, enabled = ready) { Text("Install") } },
        dismissButton = { TextButton(onClick = { inst.askInstall = false }) { Text("Cancel") } },
    )
}

@Composable
private fun JjUninstallDialog(inst: JjAaController) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = { inst.askUninstall = false },
        title = { Text("Uninstall Android Auto?") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("This stops the Android Auto service, then removes:", fontSize = 13.sp)
                Text(JjAa.RT_DIR, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                Text(JjAa.PLUGIN_DIR, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "Nothing of this package is kept, so a later install starts fresh. Afterwards restart iDrive.",
                    fontSize = 12.sp,
                    color = soft,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { inst.uninstall() }) { Text("Uninstall", color = Red) } },
        dismissButton = { TextButton(onClick = { inst.askUninstall = false }) { Text("Cancel") } },
    )
}

@Composable
private fun JjRunDialog(inst: JjAaController, d: RunState) {
    val ok = d.status == RunStatus.OK
    var showLog by remember { mutableStateOf(d.status == RunStatus.RUNNING) }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val bodyMax = screenH * 0.7f - 150.dp
    val logMax = (bodyMax - 170.dp).coerceAtLeast(120.dp)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val successContainer = if (isDark) Color(0xFF362C0A) else Color(0xFFFBF2D2)
    val what = when (d.kind) {
        RunKind.INSTALL -> "Install"
        RunKind.UNINSTALL -> "Uninstall"
        else -> "Run"
    }
    AlertDialog(
        onDismissRequest = { if (d.status != RunStatus.RUNNING) inst.closeRun() },
        containerColor = if (ok) successContainer else AlertDialogDefaults.containerColor,
        modifier = if (ok) Modifier.border(2.dp, JjYellow, RoundedCornerShape(28.dp)) else Modifier,
        title = {
            Text(
                when (d.status) {
                    RunStatus.RUNNING -> "$what…"
                    RunStatus.OK -> "$what done"
                    RunStatus.FAIL -> "$what failed"
                },
                color = when (d.status) {
                    RunStatus.OK -> JjYellow
                    RunStatus.FAIL -> Red
                    RunStatus.RUNNING -> Color.Unspecified
                },
            )
        },
        text = {
            Column(Modifier.heightIn(max = bodyMax).verticalScroll(rememberScrollState())) {
                when (d.status) {
                    RunStatus.RUNNING -> {
                        Text(d.lines.lastOrNull()?.text ?: "Starting…", fontSize = 13.sp)
                        inst.liveLabel?.let { Text("Uploading $it…", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                        val f = inst.liveFraction
                        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                    RunStatus.OK -> {
                        Text(d.headline, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = JjYellow)
                        for (m in d.notes) Text(m, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            if (d.kind == RunKind.UNINSTALL) {
                                "Restart iDrive: the Android Auto entry leaves the Media/Radio menu then."
                            } else {
                                "Restart iDrive, then Multimedia -> Android Auto / CarPlay."
                            },
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    RunStatus.FAIL -> Text(d.headline, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Red)
                }
                if (d.status != RunStatus.RUNNING) {
                    TextButton(onClick = { showLog = !showLog }, contentPadding = PaddingValues(vertical = 4.dp)) {
                        Text(if (showLog) "Hide the log" else "Show the log", fontSize = 12.sp)
                    }
                }
                if (showLog) LogConsole(d.lines, logMax)
            }
        },
        confirmButton = { TextButton(onClick = { inst.closeRun() }, enabled = d.status != RunStatus.RUNNING) { Text("Close") } },
    )
}

@Composable
private fun JjCreditsDialog(inst: JjAaController) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { inst.showCredits = false },
        title = { Text("Credits and licence") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(JjAa.CREDITS, fontSize = 12.sp)
                inst.view?.payload?.permissionText?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Permission text from the package:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { inst.showCredits = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { uri.openUri(JjAa.PROJECT_URL) }) { Text("Open the project") } },
    )
}
