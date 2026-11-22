package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.ShelfIndex

/**
 * The Add-ons page: the Android Auto tile and the menu look (deathwalker80's extras). Each comes as a zip: pick it from
 * the phone, or choose one that was kept on the phone. The zip is kept, so it is still there when no car is connected.
 */
@Composable
fun AddOnsScreen(vm: MainViewModel) {
    BackHandler { vm.backToHub() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.pickAddOn(uris)
    }
    val kept = vm.inst.shelf.filter { it.summary == ShelfIndex.TILE_KIND || it.summary == ShelfIndex.MENU_LOOK_KIND }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(52.dp))
        Text("Add-ons", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Extras by deathwalker80 that come as zip files. The app reads the zip, checks it and does the steps itself.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.addOnBusy, modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
            Text("A ZIP FROM THIS PHONE…")
        }
        Text(
            "Pick nbt-aa-tile-5.zip or aa-menu-look-2.zip. The app recognizes which one it is and keeps a copy on the phone, " +
                "so it can be used later when the car is connected.",
            fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
        if (vm.addOnBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (vm.addOnMessage.isNotEmpty()) {
            Text(vm.addOnMessage, fontSize = 12.sp, color = if (vm.addOnBusy) muted else Red, modifier = Modifier.padding(top = 8.dp))
        }

        AddOnCard(
            title = "Android Auto tile",
            what = "A tile in the iDrive main menu. Pressing it brings Android Auto back. Test it for 2 minutes first, then install it at boot; one tap removes it.",
            loaded = vm.tile.shown?.kit?.title,
            onOpen = { vm.openTilePage() },
        )
        AddOnCard(
            title = "Menu look",
            what = "Makes the CarLife entry of the iDrive menu read \"Android Auto\" in every language, with your own pictures if you like. Check first; the originals are kept and can be restored.",
            loaded = vm.menuLook.shown?.kit?.title,
            onOpen = { vm.openMenuLookPage() },
        )

        if (kept.isNotEmpty()) {
            Text("Saved on this phone", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp))
            for (item in kept) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.summary + (item.version?.let { " · $it" } ?: "") + " · " + item.origin, fontSize = 11.sp, color = muted)
                    }
                    TextButton(onClick = { vm.inst.useShelf(item) }) { Text("CHOOSE", fontSize = 12.sp) }
                }
            }
        }

        Text(
            "Where to get the zips: Browse community → the page that shares them → LOOK INSIDE → TRY TO INSTALL. " +
                "Or download them to the phone and pick them above.",
            fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 20.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun AddOnCard(title: String, what: String, loaded: String?, onOpen: () -> Unit) {
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(what, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (loaded != null) "✓ $loaded is loaded." else "No file loaded yet.",
            fontSize = 12.sp, color = if (loaded != null) Green else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedButton(onClick = onOpen, enabled = loaded != null, modifier = Modifier.padding(top = 6.dp)) {
            Text("OPEN THE PAGE", fontSize = 12.sp)
        }
    }
}
