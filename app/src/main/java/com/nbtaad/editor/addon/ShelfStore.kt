package com.nbtaad.editor.addon

import android.content.Context
import com.nbtaad.editor.install.BuildPackage
import com.nbtaad.editor.install.SourceFile
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.update.UpdateLogic
import java.io.File

/**
 * Keeps installable zips in the app's private storage (files/shelf). The bytes of a file are stored under its hash,
 * never under the name it came with, so a name cannot place a file anywhere; the list is a small JSON file next to them.
 * Nothing leaves the phone and the other apps cannot read it.
 */
class ShelfStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "shelf")
    private val index = File(dir, "index.json")

    private fun bytesOf(id: String) = File(dir, "$id.bin")

    @Synchronized
    fun list(): List<ShelfItem> {
        val items = try { if (index.exists()) ShelfIndex.parse(index.readText()) else emptyList() } catch (_: Exception) { emptyList() }
        // an entry whose bytes are gone is no entry
        return items.filter { bytesOf(it.id).isFile }.sortedByDescending { it.savedAt }
    }

    private fun write(items: List<ShelfItem>) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ShelfIndex.toJson(items))
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    /** Keeps [file]. Returns the entry, or null when it is not an installable build (nothing is kept then). */
    @Synchronized
    fun save(file: SourceFile, origin: String, pageUrl: String?): ShelfItem? {
        val pkg = try { BuildPackage.of(listOf(file)) } catch (_: Exception) { return null }
        if (!pkg.installable) return null
        val id = UpdateLogic.sha256Hex(file.bytes).take(16)
        dir.mkdirs()
        val target = bytesOf(id)
        if (!target.isFile || target.length() != file.bytes.size.toLong()) {
            val tmp = File(dir, "$id.tmp")
            tmp.writeBytes(file.bytes)
            target.delete()
            tmp.renameTo(target)
        }
        val item = ShelfItem(
            id, file.name, System.currentTimeMillis(), file.bytes.size.toLong(), pkg.version, ShelfIndex.kindText(pkg.kind),
            origin, pageUrl,
        )
        val (kept, dropped) = ShelfIndex.place(list(), item)
        for (d in dropped) bytesOf(d.id).delete()
        write(kept)
        AppLog.i("Shelf", "Kept ${file.name} (${file.bytes.size} bytes, ${pkg.version ?: "no version"}); ${kept.size} on the shelf" +
            if (dropped.isNotEmpty()) ", ${dropped.size} older one(s) made room" else "")
        return item
    }

    /** The bytes of an entry as a file to install, or null when they are gone or no longer match their hash. */
    @Synchronized
    fun load(item: ShelfItem): SourceFile? {
        val f = bytesOf(item.id)
        if (!f.isFile) return null
        val bytes = try { f.readBytes() } catch (_: Exception) { return null }
        if (!UpdateLogic.sha256Hex(bytes).startsWith(item.id)) {
            AppLog.w("Shelf", "${item.name} on the shelf does not match its hash any more, removing it")
            remove(item.id)
            return null
        }
        return SourceFile(item.name, bytes)
    }

    @Synchronized
    fun remove(id: String) {
        bytesOf(id).delete()
        write(list().filter { it.id != id })
        AppLog.i("Shelf", "Removed $id from the shelf")
    }

    @Synchronized
    fun markInstalled(id: String) {
        val now = System.currentTimeMillis()
        write(list().map { if (it.id == id) it.copy(installedAt = now) else it })
    }
}
