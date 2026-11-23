package com.nbtaad.editor.addon

import com.nbtaad.editor.experimental.AiLogic
import com.nbtaad.editor.experimental.Json
import com.nbtaad.editor.experimental.asList
import com.nbtaad.editor.experimental.asObj
import com.nbtaad.editor.experimental.str
import com.nbtaad.editor.install.Kind

/*
 * The shelf: installable zips that were kept on the phone, so that they are still there when the app was closed or
 * no head unit was connected when they were found. These are the parts that need no Android: what a shelf entry
 * holds, how the list is written down and read back, and which entries make room for a new one.
 */

/** One kept file. [id] is the start of the SHA-256 of its bytes, so keeping the same file twice is one entry. */
class ShelfItem(
    val id: String,
    val name: String,
    val savedAt: Long,
    val size: Long,
    /** The daemon's version when the file has one ("v1.9.116"), else null. */
    val version: String?,
    /** What it is: "full build", "plugin only" … */
    val summary: String,
    /** Where it came from: "issue #226, shared by someone". */
    val origin: String,
    val pageUrl: String?,
    /** When it was last installed from the shelf, else null. */
    val installedAt: Long? = null,
    /** The address the file was downloaded from, so the same file is not downloaded again; null for older entries. */
    val sourceUrl: String? = null,
) {
    fun copy(savedAt: Long = this.savedAt, installedAt: Long? = this.installedAt) =
        ShelfItem(id, name, savedAt, size, version, summary, origin, pageUrl, installedAt, sourceUrl)

    /**
     * Is this the copy of the file at [url]? By its address when it has one; an older entry (saved before addresses
     * were kept) is matched by the file's name on the same page.
     */
    fun isCopyOf(url: String, fileName: String, page: String?): Boolean =
        if (sourceUrl != null) sourceUrl == url else (name == fileName && page != null && pageUrl == page)

    /** A kept add-on zip (the tile, the menu look): not a main NBT-AAD build, so the install page is not where it belongs. */
    val isAddOn: Boolean get() = summary == ShelfIndex.TILE_KIND || summary == ShelfIndex.MENU_LOOK_KIND
}

object ShelfIndex {
    /** How many files are kept, and how many bytes at most: the oldest make room. */
    const val MAX_ITEMS = 8
    const val MAX_BYTES = 150L * 1024 * 1024

    /** What a kept tile add-on is called on the shelf. */
    const val TILE_KIND = "Android Auto tile (add-on)"
    const val MENU_LOOK_KIND = "Menu look (add-on)"

    /** Short words for what a set of files is. */
    fun kindText(kind: Kind): String = when (kind) {
        Kind.BUILD -> "full build"
        Kind.DAEMON_ONLY -> "daemon only"
        Kind.PLUGIN_ONLY -> "plugin only"
        Kind.SOME_FILES -> "some files of a build"
        Kind.NOTHING -> "not a build"
    }

    fun toJson(items: List<ShelfItem>): String =
        "{\"items\":[" + items.joinToString(",") { i ->
            "{\"id\":${AiLogic.jsonString(i.id)},\"name\":${AiLogic.jsonString(i.name)},\"savedAt\":${i.savedAt}," +
                "\"size\":${i.size},\"version\":${i.version?.let { AiLogic.jsonString(it) } ?: "null"}," +
                "\"summary\":${AiLogic.jsonString(i.summary)},\"origin\":${AiLogic.jsonString(i.origin)}," +
                "\"pageUrl\":${i.pageUrl?.let { AiLogic.jsonString(it) } ?: "null"}," +
                "\"installedAt\":${i.installedAt ?: "null"}," +
                "\"sourceUrl\":${i.sourceUrl?.let { AiLogic.jsonString(it) } ?: "null"}}"
        } + "]}"

    private val ID = Regex("""[0-9a-f]{8,64}""")

    /** The list as written by [toJson]; an unreadable file gives an empty list, an odd entry is skipped. */
    fun parse(text: String): List<ShelfItem> {
        val root = try { Json.parse(text).asObj() } catch (_: Exception) { null } ?: return emptyList()
        return (root["items"].asList() ?: emptyList()).mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val id = o.str("id")
            if (!ID.matches(id)) return@mapNotNull null
            ShelfItem(
                id = id,
                name = o.str("name").ifBlank { "file.zip" },
                savedAt = (o["savedAt"] as? Number)?.toLong() ?: 0L,
                size = (o["size"] as? Number)?.toLong() ?: 0L,
                version = o.str("version").ifBlank { null },
                summary = o.str("summary"),
                origin = o.str("origin"),
                pageUrl = o.str("pageUrl").ifBlank { null },
                installedAt = (o["installedAt"] as? Number)?.toLong(),
                sourceUrl = o.str("sourceUrl").ifBlank { null },
            )
        }
    }

    /**
     * Puts [added] on the list (the same file again only moves to the top) and says which entries have to go to stay
     * within the limits: the oldest first, never [added] itself. Returns the new list, newest first, and the dropped.
     */
    fun place(old: List<ShelfItem>, added: ShelfItem, maxItems: Int = MAX_ITEMS, maxBytes: Long = MAX_BYTES): Pair<List<ShelfItem>, List<ShelfItem>> {
        val previous = old.firstOrNull { it.id == added.id }
        val top = if (previous != null) added.copy(installedAt = previous.installedAt) else added
        val rest = old.filter { it.id != added.id }.sortedByDescending { it.savedAt }.toMutableList()
        val dropped = ArrayList<ShelfItem>()
        fun total() = top.size + rest.sumOf { it.size }
        while (rest.isNotEmpty() && (rest.size + 1 > maxItems || total() > maxBytes)) dropped += rest.removeAt(rest.size - 1)
        return (listOf(top) + rest) to dropped
    }
}
