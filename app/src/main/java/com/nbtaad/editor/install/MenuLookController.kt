package com.nbtaad.editor.install

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.addon.MenuLook
import com.nbtaad.editor.addon.MenuLookKit
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The page of the menu look (deathwalker80's aa-menu-look): the label of the CarLife entry ("Android Auto") in every
 * language, and the person's own pictures for it. CHECK does everything but the replacing, APPLY keeps the factory
 * copy of every file it replaces, RESTORE puts them all back.
 */
class MenuLookController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: () -> String?,
    private val rescan: () -> String?,
    private val problem: () -> String?,
) {
    class Shown(val kit: MenuLookKit, val fileName: String, val origin: String, val pageUrl: String?, val shelfId: String?)

    /** A picture the person chose for a place; [problem] says why it cannot be used there. */
    class Chosen(val name: String, val bytes: ByteArray, val problem: String?, val note: String?)

    class Result(
        val action: MenuLookScripts.Action,
        val ok: Boolean,
        val headline: String,
        val lines: List<String> = emptyList(),
        val log: String = "",
        val hint: String? = null,
    )

    var shown by mutableStateOf<Shown?>(null); private set
    var brand by mutableStateOf<String?>(null)
    var label by mutableStateOf(MenuLook.DEFAULT_LABEL)
    var pictures by mutableStateOf<Map<String, Chosen>>(emptyMap()); private set
    var pictureMessage by mutableStateOf("")
    var busy by mutableStateOf<MenuLookScripts.Action?>(null); private set
    var step by mutableStateOf(""); private set
    var report by mutableStateOf<TileScripts.Report?>(null); private set
    var result by mutableStateOf<Result?>(null); private set
    var checkedOk by mutableStateOf(false); private set
    var askApply by mutableStateOf(false)
    var askRestore by mutableStateOf(false)
    var showLog by mutableStateOf(false)

    val working: Boolean get() = busy != null

    /** The brands whose text.db the head unit has, in the order the page offers them. */
    val brandsFound: List<String>
        get() = report?.let { r -> MenuLook.BRANDS.filter { r.state["brand-$it"] == "yes" } } ?: emptyList()

    val labelProblem: String? get() = MenuLook.labelProblem(label)

    val installed: Boolean get() = report?.state?.get("installed") == "yes"
    val installedBrand: String? get() = report?.state?.get("ibrand")?.takeIf { it.isNotBlank() }
    val installedFiles: Int get() = report?.state?.get("files")?.toIntOrNull() ?: 0

    fun show(s: Shown) {
        if (working) return
        AppLog.i("MenuLook", "Opened ${s.kit.title} from ${s.origin} (known build ${s.kit.known})")
        shown = s
        result = null
        checkedOk = false
    }

    fun look() = go(MenuLookScripts.Action.LOOK)
    fun check() = go(MenuLookScripts.Action.CHECK)

    fun askToApply() {
        if (!working && canApply) askApply = true
    }

    fun apply() {
        askApply = false
        go(MenuLookScripts.Action.APPLY)
    }

    fun askToRestore() {
        if (!working) askRestore = true
    }

    fun restore() {
        askRestore = false
        go(MenuLookScripts.Action.RESTORE)
    }

    /** True when the kit, the brand, the label and every chosen picture are in order. */
    val canApply: Boolean
        get() = shown?.kit?.ok == true && brand != null && labelProblem == null && usablePictures().size == pictures.filterKeys { it in slotRels() }.size

    private fun slotRels(): Set<String> = brand?.let { b -> MenuLook.slots(b).map { it.rel }.toSet() } ?: emptySet()

    private fun usablePictures(): List<MenuLookHeadUnit.Picture> =
        pictures.filterKeys { it in slotRels() }.filterValues { it.problem == null }.map { MenuLookHeadUnit.Picture(it.key, it.value.bytes) }

    fun chooseBrand(b: String) {
        if (working) return
        brand = b
        checkedOk = false
    }

    fun clearPicture(rel: String) {
        pictures = pictures - rel
        checkedOk = false
    }

    /** Reads a PNG the person picked for the place [rel] and checks its size against what the place needs. */
    fun choosePicture(rel: String, uri: Uri) {
        val slot = brand?.let { b -> MenuLook.slots(b).firstOrNull { it.rel == rel } } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: throw java.io.IOException("cannot open it")
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "picture.png"
                pictures = pictures + (rel to Chosen(name, bytes, MenuLook.slotProblem(slot, bytes), MenuLook.slotNote(slot, bytes)))
                pictureMessage = ""
                checkedOk = false
            } catch (e: Exception) {
                AppLog.w("MenuLook", "Could not read the picture: ${e.message}")
                pictureMessage = "Could not read that picture: ${e.message ?: e.javaClass.simpleName}."
            }
        }
    }

    private fun go(action: MenuLookScripts.Action) {
        if (working) return
        val kit = shown?.kit
        val sending = action == MenuLookScripts.Action.CHECK || action == MenuLookScripts.Action.APPLY
        if (sending && (kit == null || !kit.ok)) return
        val useBrand = brand ?: if (sending) return else "bmw"
        val useLabel = if (sending) {
            if (labelProblem != null) return
            label
        } else MenuLook.DEFAULT_LABEL
        val pics = if (sending) usablePictures() else emptyList()
        busy = action
        step = "Starting…"
        if (action != MenuLookScripts.Action.LOOK) result = null
        AppLog.i("MenuLook", "${action.word} started (brand $useBrand, ${pics.size} pictures)")
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val rep = Reporter(say = { t, _ -> step = t }, progress = { _, _, _ -> })
                val r = runOnHeadUnit(host, rescan, onRescan = { step = "Looking for the car…" }, whyNot = problem) {
                    MenuLookHeadUnit.run(it, action, kit, useBrand, useLabel, pics, rep)
                }
                report = r
                if (brand == null) brand = MenuLook.BRANDS.firstOrNull { r.state["brand-$it"] == "yes" }
                result = interpret(action, r)
                if (action == MenuLookScripts.Action.CHECK) checkedOk = r.success
            } catch (e: Exception) {
                AppLog.e("MenuLook", "${action.word} failed", e)
                result = Result(
                    action, false, friendlyError(e),
                    hint = if (action == MenuLookScripts.Action.LOOK) null else "Press LOOK AT THE HEAD UNIT to see what it has now.",
                )
            } finally {
                busy = null
                step = ""
            }
        }
    }

    private fun interpret(action: MenuLookScripts.Action, r: TileScripts.Report): Result {
        val lines = r.ok + r.info + r.warn
        val hint = if (r.remounted == false && (action == MenuLookScripts.Action.APPLY || action == MenuLookScripts.Action.RESTORE)) {
            "The head unit's system partition could not be made writable first, which is probably why."
        } else null
        if (r.error != null) return Result(action, false, r.error, lines, r.log, hint)
        return when (action) {
            MenuLookScripts.Action.LOOK -> Result(
                action, true,
                if (brandsFound.isEmpty()) "No text.db of a known brand was found" else "Found: " + brandsFound.joinToString(", ") { MenuLook.brandName(it) },
                lines,
            )
            MenuLookScripts.Action.CHECK -> Result(
                action, true, "Checked: this would work",
                listOf(
                    if (r.state["labels"] == "1") "The label would be changed in the menu texts." else "The label needs no change.",
                    "${r.state["pictures"] ?: "0"} picture(s) would be replaced.",
                ) + lines.filter { !it.startsWith("checked") },
                r.log, "Nothing on the head unit was changed. APPLY does it.",
            )
            MenuLookScripts.Action.APPLY -> Result(
                action, true, "Menu look applied", lines, r.log,
                "Restart the head unit (power button 30 s). RESTORE puts the original files back.",
            )
            MenuLookScripts.Action.RESTORE -> Result(action, true, "Original files are back", lines, "", "Restart the head unit.")
        }
    }

    private companion object {
        const val MAX_PICTURE = 2 * 1024 * 1024

        fun readLimited(input: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_PICTURE) throw java.io.IOException("it is bigger than 2 MB")
            }
            return out.toByteArray()
        }
    }
}
