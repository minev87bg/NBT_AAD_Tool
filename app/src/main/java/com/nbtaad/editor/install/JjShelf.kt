package com.nbtaad.editor.install

import android.content.Context
import com.nbtaad.editor.experimental.AiLogic
import com.nbtaad.editor.experimental.Json
import com.nbtaad.editor.experimental.asList
import com.nbtaad.editor.experimental.asObj
import com.nbtaad.editor.experimental.str
import com.nbtaad.editor.update.UpdateLogic
import java.io.File

/**
 * The shelf for JayJay3071's NBT Android Auto (original NBT): release zips kept on the phone, so they are still
 * there after the app is closed or without internet. Deliberately a separate store from [ShelfStore] - a
 * different package, in its own folder - even though the shape mirrors it closely; see net/JjAa.kt for why this
 * feature is kept apart from the rest of the app.
 */
class JjShelfItem(
    val id: String,
    val name: String,
    val savedAt: Long,
    val size: Long,
    val version: String,
    /** Was this file gotten from the project's own GitHub release (true), or from a file on the phone (false)? A
     * shelf copy of a phone file still has not been checked any more than it was the first time. */
    val fromRelease: Boolean,
    val pageUrl: String? = null,
    val installedAt: Long? = null,
    val sourceUrl: String? = null,
) {
    fun copy(installedAt: Long? = this.installedAt) =
        JjShelfItem(id, name, savedAt, size, version, fromRelease, pageUrl, installedAt, sourceUrl)

    fun isCopyOf(url: String): Boolean = sourceUrl == url
}

object JjShelfIndex {
    const val MAX_ITEMS = 5
    const val MAX_BYTES = 200L * 1024 * 1024

    fun toJson(items: List<JjShelfItem>): String =
        "{\"items\":[" + items.joinToString(",") { i ->
            "{\"id\":${AiLogic.jsonString(i.id)},\"name\":${AiLogic.jsonString(i.name)},\"savedAt\":${i.savedAt}," +
                "\"size\":${i.size},\"version\":${AiLogic.jsonString(i.version)},\"fromRelease\":${i.fromRelease}," +
                "\"pageUrl\":${i.pageUrl?.let { AiLogic.jsonString(it) } ?: "null"}," +
                "\"installedAt\":${i.installedAt ?: "null"}," +
                "\"sourceUrl\":${i.sourceUrl?.let { AiLogic.jsonString(it) } ?: "null"}}"
        } + "]}"

    private val ID = Regex("""[0-9a-f]{8,64}""")

    fun parse(text: String): List<JjShelfItem> {
        val root = try { Json.parse(text).asObj() } catch (_: Exception) { null } ?: return emptyList()
        return (root["items"].asList() ?: emptyList()).mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val id = o.str("id")
            if (!ID.matches(id)) return@mapNotNull null
            JjShelfItem(
                id = id,
                name = o.str("name").ifBlank { "file.zip" },
                savedAt = (o["savedAt"] as? Number)?.toLong() ?: 0L,
                size = (o["size"] as? Number)?.toLong() ?: 0L,
                version = o.str("version"),
                fromRelease = (o["fromRelease"] as? Boolean) ?: false,
                pageUrl = o.str("pageUrl").ifBlank { null },
                installedAt = (o["installedAt"] as? Number)?.toLong(),
                sourceUrl = o.str("sourceUrl").ifBlank { null },
            )
        }
    }

    fun place(old: List<JjShelfItem>, added: JjShelfItem, maxItems: Int = MAX_ITEMS, maxBytes: Long = MAX_BYTES): Pair<List<JjShelfItem>, List<JjShelfItem>> {
        val previous = old.firstOrNull { it.id == added.id }
        val top = if (previous != null) added.copy(installedAt = previous.installedAt) else added
        val rest = old.filter { it.id != added.id }.sortedByDescending { it.savedAt }.toMutableList()
        val dropped = ArrayList<JjShelfItem>()
        fun total() = top.size + rest.sumOf { it.size }
        while (rest.isNotEmpty() && (rest.size + 1 > maxItems || total() > maxBytes)) dropped += rest.removeAt(rest.size - 1)
        return (listOf(top) + rest) to dropped
    }
}

class JjShelfStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "jj_shelf")
    private val index = File(dir, "index.json")

    private fun bytesOf(id: String) = File(dir, "$id.zip")

    @Synchronized
    fun list(): List<JjShelfItem> {
        val items = try { if (index.exists()) JjShelfIndex.parse(index.readText()) else emptyList() } catch (_: Exception) { emptyList() }
        return items.filter { bytesOf(it.id).isFile }.sortedByDescending { it.savedAt }
    }

    private fun write(items: List<JjShelfItem>) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(JjShelfIndex.toJson(items))
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    /** Keeps [bytes] (already confirmed by [com.nbtaad.editor.net.JjAa.parsePayloadZip]) as [name]. */
    @Synchronized
    fun save(bytes: ByteArray, name: String, version: String, fromRelease: Boolean, pageUrl: String?, sourceUrl: String?): JjShelfItem {
        val id = UpdateLogic.sha256Hex(bytes).take(16)
        dir.mkdirs()
        val target = bytesOf(id)
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            val tmp = File(dir, "$id.tmp")
            tmp.writeBytes(bytes)
            target.delete()
            tmp.renameTo(target)
        }
        val item = JjShelfItem(id, name, System.currentTimeMillis(), bytes.size.toLong(), version, fromRelease, pageUrl, sourceUrl = sourceUrl)
        val (kept, dropped) = JjShelfIndex.place(list(), item)
        for (d in dropped) bytesOf(d.id).delete()
        write(kept)
        return item
    }

    @Synchronized
    fun copyOf(url: String): JjShelfItem? = list().firstOrNull { it.isCopyOf(url) }

    @Synchronized
    fun load(item: JjShelfItem): ByteArray? {
        val f = bytesOf(item.id)
        if (!f.isFile) return null
        val bytes = try { f.readBytes() } catch (_: Exception) { return null }
        if (!UpdateLogic.sha256Hex(bytes).startsWith(item.id)) {
            remove(item.id)
            return null
        }
        return bytes
    }

    @Synchronized
    fun remove(id: String) {
        bytesOf(id).delete()
        write(list().filter { it.id != id })
    }

    @Synchronized
    fun markInstalled(id: String) {
        val now = System.currentTimeMillis()
        write(list().map { if (it.id == id) it.copy(installedAt = now) else it })
    }
}
