package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.AddOnCatalog
import com.nbtaad.editor.addon.CatalogEntry
import com.nbtaad.editor.addon.ShelfIndex

/**
 * The Add-ons page: a list of the add-ons the app has approved. Each card says who made it and what it does, shows the
 * newest release on its GitHub page, and its button gets that release (or opens the copy kept on the phone).
 */
@Composable
fun AddOnsScreen(vm: MainViewModel) {
    BackHandler { vm.backToHub() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val a = vm.addOns
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.pickAddOn(uris)
    }
    val strangers = vm.inst.shelf.filter { it.summary == ShelfIndex.TILE_KIND || it.summary == ShelfIndex.MENU_LOOK_KIND }
    val followed = AddOnCatalog.PROJECTS.joinToString(", ") { it.name }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Text("Add-ons", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Extras for NBT-AAD that the app has approved. Each one is followed on its GitHub page: the app takes its newest " +
                "release, and its page does the install steps.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    a.loading -> "Checking GitHub…"
                    a.loadMessage.isNotEmpty() -> a.loadMessage
                    a.checkedAt != 0L -> "Followed on GitHub: $followed"
                    else -> ""
                },
                fontSize = 11.sp, color = if (a.loadMessage.isNotEmpty() && !a.loading) Red else muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { a.refresh() }, enabled = !a.loading) { Text("CHECK AGAIN", fontSize = 12.sp) }
        }
        if (a.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        for (entry in AddOnCatalog.ENTRIES) AddOnCard(vm, entry)

        Text("Have the zip already?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 22.dp))
        Text(
            "A zip of an approved add-on that is on this phone: the app recognizes which one it is and keeps a copy.",
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.addOnBusy, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Text("A ZIP FROM THIS PHONE…")
        }
        if (vm.addOnBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (vm.addOnMessage.isNotEmpty()) {
            Text(vm.addOnMessage, fontSize = 12.sp, color = if (vm.addOnBusy) muted else Red, modifier = Modifier.padding(top = 8.dp))
        }

        if (strangers.isNotEmpty()) {
            Text("Saved on this phone", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp))
            for (item in strangers) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.summary + (item.version?.let { " · $it" } ?: "") + " · " + item.origin, fontSize = 11.sp, color = muted)
                    }
                    TextButton(onClick = { vm.inst.useShelf(item) }) { Text("CHOOSE", fontSize = 12.sp) }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AddOnCard(vm: MainViewModel, entry: CatalogEntry) {
    val a = vm.addOns
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val found = a.latest(entry)
    val plan = a.plan(entry)
    val busy = a.busyId == entry.id
    val message = a.messages[entry.id]
    var notes by remember { mutableStateOf(false) }
    // a picture of the add-on, when the app has one (a drawable called like the entry's screenshot name)
    val shot = remember(entry.screenshot) { entry.screenshot?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) } ?: 0 }

    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (shot != 0) {
            Image(
                painterResource(shot), contentDescription = entry.title, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("by ${entry.author}", fontSize = 12.sp, color = muted)
            }
            TextButton(onClick = { openLink(uri, entry.project.pageUrl) }) { Text("GITHUB", fontSize = 12.sp) }
        }
        Text(entry.summary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text(plan.status, fontSize = 12.sp, color = if (plan.doing == AddOnCatalog.Do.UPDATE) Amber else muted, modifier = Modifier.padding(top = 6.dp))
        if (found != null) {
            Text(
                "Release ${found.release.tag} · ${found.release.published.take(10)}" + if (found.release.prerelease) " · pre-release" else "",
                fontSize = 11.sp, color = muted,
            )
        }
        OutlinedButton(
            onClick = { a.act(entry) },
            enabled = a.busyId == null && plan.doing in setOf(AddOnCatalog.Do.OPEN_SAVED, AddOnCatalog.Do.GET, AddOnCatalog.Do.UPDATE),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        ) { Text(if (busy) "GETTING…" else plan.label, fontSize = 13.sp) }
        if (busy) {
            Text(a.step, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (message != null) Text(message, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp))
        if (found != null && found.release.notes.isNotBlank()) {
            TextButton(onClick = { notes = !notes }) { Text(if (notes) "HIDE WHAT'S NEW" else "WHAT'S NEW IN ${found.release.tag}", fontSize = 12.sp) }
            if (notes) Text(AddOnCatalog.notesDigest(found.release.notes), fontSize = 12.sp, color = muted)
        }
    }
}
