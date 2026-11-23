package com.nbtaad.editor.addon

import com.nbtaad.editor.install.Elf
import com.nbtaad.editor.update.UpdateLogic
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * deathwalker80's aa-menu-look: makes the CarLife entry of the iDrive menu look like Android Auto. Its program
 * (aa-look, a QNX ARM program) rewrites the head unit's text.db on the head unit itself (the label, in every
 * language) and checks the pictures; the pictures are the person's own PNGs. Of the zip the app uses only aa-look: the
 * zip's own scripts (install.sh, restore.sh, list-images.sh) are not run, the app does the same steps itself.
 */

/** The aa-look program of a zip, and what the app found wrong with it. */
class MenuLookKit(
    val program: ByteArray?,
    /** "2" of "aa-menu-look 2" when the zip's install.sh says so. */
    val version: String?,
    val known: Boolean,
    val problems: List<String>,
    val notes: List<String>,
) {
    val ok: Boolean get() = problems.isEmpty() && program != null
    /** The build number on its own ("2"), not folded into [title] any more: the page shows freshness next to the name instead. */
    val title: String get() = "aa-menu-look"
}

object MenuLookKits {
    const val PROGRAM = "aa-look"
    private const val SIZE = "aa-look.size"
    private const val SUMS = "SHA256SUMS"
    private const val INSTALL = "install.sh"
    private val WANTED = listOf(PROGRAM, SIZE, SUMS, INSTALL)

    /** SHA-256 of the aa-look of builds the app was written for: version to hash. */
    val KNOWN: Map<String, String> = mapOf(
        "2" to "adaeec2bc0834e77be4edc1d9b8cfaf3e26e174755e19c7e17b8c4e622114a3b",
    )

    private const val MAX_ENTRY = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 300
    private val VERSION = Regex("""aa-menu-look (\d+)""")

    fun parse(zip: ByteArray): MenuLookKit {
        val found = LinkedHashMap<String, ByteArray>()
        val seen = HashMap<String, Int>()
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (++count > MAX_ENTRIES) return MenuLookKit(null, null, false, listOf("The zip holds too many files."), emptyList())
                    val base = e.name.replace('\\', '/').substringAfterLast('/')
                    if (base !in WANTED) continue
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_ENTRY) return MenuLookKit(null, null, false, listOf("$base is far bigger than the menu look's files are."), emptyList())
                    }
                    seen[base] = (seen[base] ?: 0) + 1
                    found[base] = out.toByteArray()
                }
            }
        } catch (e: Exception) {
            return MenuLookKit(null, null, false, listOf("This is not a zip the app can read."), emptyList())
        }
        val dup = seen.filterValues { it > 1 }.keys.map { "$it is in the zip more than once, so the app cannot tell which one is meant." }
        return of(found, dup)
    }

    fun of(all: Map<String, ByteArray>, earlier: List<String> = emptyList()): MenuLookKit {
        val problems = ArrayList(earlier)
        val notes = ArrayList<String>()
        val program = all[PROGRAM]
        if (program == null) return MenuLookKit(null, null, false, problems + "$PROGRAM is not in the zip.", notes)

        val sums = all[SUMS]?.toString(Charsets.UTF_8)?.let(TileKits::parseSums)
        if (sums == null) notes += "The zip has no SHA256SUMS list: only the app's own checks were done."
        else when (val want = sums[PROGRAM]) {
            null -> notes += "SHA256SUMS does not list $PROGRAM."
            else -> if (!want.equals(UpdateLogic.sha256Hex(program), ignoreCase = true)) problems += "$PROGRAM does not match SHA256SUMS: it was changed or is incomplete."
        }
        all[SIZE]?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull()?.let {
            if (it != program.size.toLong()) problems += "$PROGRAM is ${program.size} bytes but $SIZE says $it."
        }
        val elf = Elf.parse(program)
        when {
            elf == null -> problems += "$PROGRAM is not a program (no ELF header)."
            !elf.isArm32 || !elf.qnx || elf.type != 2 && elf.type != 3 -> problems += "$PROGRAM is not a 32-bit ARM program for QNX, so the head unit cannot run it."
        }
        val text = String(program, Charsets.ISO_8859_1)
        if ("usage: aa-look labels" !in text) problems += "$PROGRAM does not look like the menu look's label program."

        val version = all[INSTALL]?.toString(Charsets.UTF_8)?.let { VERSION.find(it)?.groupValues?.get(1) }
        val hash = UpdateLogic.sha256Hex(program)
        val known = KNOWN.entries.firstOrNull { it.value == hash }?.key
        if (known == null && problems.isEmpty()) {
            notes += "This aa-look is not identical to a build the app knows (${KNOWN.keys.joinToString { "aa-menu-look $it" }}). It passed the app's checks, but the program has not been looked at."
        }
        return MenuLookKit(program, version ?: known, known != null, problems, notes)
    }
}

/** The pictures of the CarLife entry, the brands, and the label's rules. */
object MenuLook {
    val BRANDS = listOf("bmw", "bmwm", "bmwi", "mini")
    const val DEFAULT_LABEL = "Android Auto"

    /** What a brand is called on the page. */
    fun brandName(b: String): String = when (b) {
        "bmw" -> "BMW"
        "bmwm" -> "BMW (M texts)"
        "bmwi" -> "BMW i"
        "mini" -> "MINI"
        else -> b
    }

    private val ALLOWED = Regex("""[A-Za-z0-9 .,&()+\-_/:]+""")

    /** Why [label] cannot be used, or null. The program takes 1 to 40 plain ASCII characters; the app allows the harmless ones. */
    fun labelProblem(label: String): String? = when {
        label.isBlank() -> "The label is empty."
        label.length > 40 -> "The label is longer than 40 characters."
        label != label.trim() -> "The label starts or ends with a space."
        !ALLOWED.matches(label) -> "Use letters, digits, spaces and . , & ( ) + - _ / : only."
        else -> null
    }

    /** One picture the head unit has: its place under /opt/hmi/ID5/data/ro/, the size and colour type it must have. */
    class Slot(val rel: String, val width: Int, val height: Int, val colorType: Int, val what: String) {
        val sizeText: String get() = "$width×$height"
    }

    private val NAMES = mapOf(2 to "RGB", 4 to "grey+alpha", 6 to "RGBA")

    fun colorName(t: Int): String = NAMES[t] ?: "type $t"

    /** The pictures the author's README lists for [brand]. A file that is not on a head unit is skipped by the job. */
    fun slots(brand: String): List<Slot> = when (brand) {
        "mini" -> listOf(
            Slot("mini/id6l/assetDB/2D/Baukasten/MainMenu/Widget_Baidu_Apple/global_widget_baidu.png", 152, 152, 6, "Main-menu tile picture"),
            Slot("mini/id6l/assetDB/2D/Icons/Icons_MainMenu/36x36/MainMenu_Carlife/mainmenu_carlife.png", 36, 36, 4, "List-menu icon (white/grey)"),
            Slot("mini/id6l/assetDB/2D/Icons/Icons_MainMenu/36x36/MainMenu_Carlife/seeall_carlife.png", 128, 128, 6, "Overview icon"),
            Slot("mini/id6l/assetDB/12_baidu/carlife_48x48/carlife_48x48.png", 48, 48, 6, "Media/Radio source entry"),
        )
        "bmw", "bmwm", "bmwi" -> listOf(
            Slot("$brand/id6l/widgetasset/CarLifePad/resources/carlife_inactive.png", 72, 378, 6, "Main-menu tile"),
            Slot("$brand/id6l/widgetasset/CarLifePad/resources/carlife_selected.png", 72, 378, 6, "Main-menu tile in focus"),
            Slot("$brand/id6l/widgetasset/CarLifePad/resources/carlife_touch.png", 72, 378, 6, "Main-menu tile pressed"),
            Slot("common/assetDB/12_baidu/carlife_48x48/carlife_48x48.png", 48, 48, 6, "Media/Radio source entry"),
        )
        else -> emptyList()
    }

    /** What a PNG file says about itself. */
    class Png(val width: Int, val height: Int, val colorType: Int)

    /** The size and colour type from the PNG header, or null when the bytes are no PNG. */
    fun png(b: ByteArray): Png? {
        if (b.size < 33) return null
        val sig = intArrayOf(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) if ((b[i].toInt() and 0xFF) != sig[i]) return null
        if (String(b, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        fun u32(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        val w = u32(16)
        val h = u32(20)
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        return Png(w, h, b[25].toInt() and 0xFF)
    }

    /** What is wrong with [bytes] as the picture for [slot], or null when it fits. */
    fun slotProblem(slot: Slot, bytes: ByteArray): String? {
        val p = png(bytes) ?: return "This is not a PNG picture."
        if (p.width != slot.width || p.height != slot.height) return "It is ${p.width}×${p.height}; this place needs exactly ${slot.sizeText}."
        return null
    }

    /** A note when the picture's colour type differs from the original's (it still works). */
    fun slotNote(slot: Slot, bytes: ByteArray): String? {
        val p = png(bytes) ?: return null
        return if (p.colorType != slot.colorType) "Colour type ${colorName(p.colorType)}, the original is ${colorName(slot.colorType)}." else null
    }
}
