package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.AddOnCatalog
import com.nbtaad.editor.addon.CatalogEntry
import com.nbtaad.editor.experimental.Markdown
import kotlin.math.pow

/**
 * The Add-ons page: a list of the add-ons the app has approved, and under each one its own page. A row of the list
 * shows what it is on the left and, on the right, a picture of what it does (the space is kept for it until the picture
 * is in the app). The page of an add-on says who made it and what it does, shows the newest release on its GitHub page,
 * and its button gets that release (or opens the copy kept on the phone).
 */
@Composable
fun AddOnsScreen(vm: MainViewModel) {
    val open = vm.addOnOpen?.let { id -> AddOnCatalog.ENTRIES.firstOrNull { it.id == id } }
    BackHandler { if (open != null) vm.addOnOpen = null else vm.backToHub() }
    if (open != null) AddOnPage(vm, open) else AddOnList(vm)
}

@Composable
private fun AddOnList(vm: MainViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val a = vm.addOns
    val followed = AddOnCatalog.PROJECTS.joinToString(", ") { it.name }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Text("Add-ons", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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

        AddOnCatalog.ENTRIES.forEachIndexed { i, entry -> AddOnRow(vm, entry, i) }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * One add-on in the list: tinted with the accent, the first row strongest and each one below it fainter (never less
 * than a faint tint, so the last one is still a card), its text on the left, vertically centred, and the picture slot on the right.
 */
@Composable
private fun AddOnRow(vm: MainViewModel, entry: CatalogEntry, index: Int) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val plan = vm.addOns.plan(entry)
    val tint = (0.16f * 0.78f.pow(index)).coerceAtLeast(0.05f)
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = tint))
            .clickable { vm.addOnOpen = entry.id }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(entry.summary, fontSize = 12.sp, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(
                plan.status, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (plan.doing == AddOnCatalog.Do.UPDATE) Amber else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        PictureSlot(entry, Modifier.size(width = 136.dp, height = 82.dp))
    }
}

/** The picture of what the add-on does, when the app has one; else the same space, outlined, so the list does not move when it comes. */
@Composable
private fun PictureSlot(entry: CatalogEntry, modifier: Modifier) {
    val ctx = LocalContext.current
    val shot = remember(entry.screenshot) { entry.screenshot?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) } ?: 0 }
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    if (shot != 0) {
        Image(painterResource(shot), contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(10.dp)))
    } else {
        Box(
            modifier.drawBehind {
                drawRoundRect(
                    line, cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            },
        )
    }
}

/** The page of one add-on. */
@Composable
private fun AddOnPage(vm: MainViewModel, entry: CatalogEntry) {
    val a = vm.addOns
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val found = a.latest(entry)
    val plan = a.plan(entry)
    val busy = a.busyId == entry.id
    val message = a.messages[entry.id]
    var notes by remember { mutableStateOf(false) }
    val shot = remember(entry.screenshot) { entry.screenshot?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) } ?: 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(44.dp))
        TextButton(onClick = { vm.addOnOpen = null }) { Text("‹ ALL ADD-ONS", fontSize = 12.sp) }
        if (shot != 0) {
            Image(
                painterResource(shot), contentDescription = entry.title, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text("by ${entry.author}", fontSize = 12.sp, color = muted)
            }
            TextButton(onClick = { openLink(uri, entry.project.pageUrl) }) { Text("GITHUB", fontSize = 12.sp) }
        }
        Text(entry.summary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(plan.status, fontSize = 12.sp, color = if (plan.doing == AddOnCatalog.Do.UPDATE) Amber else muted, modifier = Modifier.padding(top = 8.dp))
        if (found != null) {
            Text(
                "Release ${found.release.tag} · ${found.release.published.take(10)}" + if (found.release.prerelease) " · pre-release" else "",
                fontSize = 11.sp, color = muted,
            )
        }
        OutlinedButton(
            onClick = { a.act(entry) },
            enabled = a.busyId == null && plan.doing in setOf(AddOnCatalog.Do.OPEN_SAVED, AddOnCatalog.Do.GET, AddOnCatalog.Do.UPDATE),
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        ) { Text(if (busy) "GETTING…" else plan.label, fontSize = 13.sp) }
        if (busy) {
            Text(a.step, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (message != null) Text(message, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 6.dp))
        if (found != null && found.release.notes.isNotBlank()) {
            TextButton(onClick = { notes = !notes }) { Text(if (notes) "HIDE WHAT'S NEW" else "WHAT'S NEW IN ${found.release.tag}", fontSize = 12.sp) }
            if (notes) {
                // the notes are Markdown from GitHub: links in them open, bold and code look as they do there
                val blocks = remember(found.release.notes) { Markdown.parse(AddOnCatalog.notesDigest(found.release.notes)) }
                MarkdownView(blocks, size = 12.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
