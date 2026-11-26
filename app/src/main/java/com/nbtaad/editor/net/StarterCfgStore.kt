package com.nbtaad.editor.net

import android.content.Context
import java.io.File

/**
 * A small library of stock `/opt/sys/etc/starter.cfg` files, one per SWFL build - never a single
 * "whichever car was last connected" slot. Different NBT EVO builds can genuinely differ here (see
 * [StarterCfgPatch]'s own fail-safe, format-aware parsing), and the entire point of flashing a
 * *stock* file back is that it actually came from a unit of the exact build being flashed - never
 * guessed, never borrowed from a different build just because it was the one already on hand.
 *
 * Keyed on the SWFL version tag read via [FlashWrite.readCoreSwflVersion]. An entry only ever enters
 * the library together with a confirmed tag - see [importStock]. Each build's own entry doubles as
 * the only safe "stock" to revert that build to.
 *
 * Not a secret - it's a QNX system config file, not a signing key - so plain app-private storage,
 * not Android-Keystore-encrypted.
 */
class StarterCfgStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "starter_cfg").apply { mkdirs() }

    init {
        migrateLegacySingleSlot()
    }

    private fun safeTag(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun fileFor(tag: String) = File(dir, "starter.cfg.${safeTag(tag)}.stock")

    /** One-time upgrade from the previous single-slot layout (one `starter.cfg.stock` plus a sidecar
     * `starter.cfg.tag`, holding whichever car was imported last) to this per-build library, so the
     * one real sample already captured isn't lost. */
    private fun migrateLegacySingleSlot() {
        val legacyStock = File(dir, "starter.cfg.stock")
        val legacyTag = File(dir, "starter.cfg.tag")
        if (!legacyStock.exists()) return
        val tag = legacyTag.takeIf { it.exists() }?.readText()?.trim()
        if (!tag.isNullOrBlank() && !fileFor(tag).exists()) {
            legacyStock.copyTo(fileFor(tag), overwrite = false)
        }
        legacyStock.delete()
        legacyTag.delete()
    }

    /** Every build currently in the library, most recently imported first. */
    fun tags(): List<String> =
        (dir.listFiles { f -> f.name.startsWith("starter.cfg.") && f.name.endsWith(".stock") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removePrefix("starter.cfg.").removeSuffix(".stock") })
            ?: emptyList()

    fun hasStock(tag: String): Boolean = fileFor(tag).exists()

    fun readStock(tag: String): ByteArray? = fileFor(tag).takeIf { it.exists() }?.readBytes()

    /** [tag] is the core SWFL version ([FlashWrite.readCoreSwflVersion]) this file was read
     * alongside - required, never null: a file whose build can't be confirmed must never enter the
     * library, since there would be nothing to safely match it against before a later flash.
     * Re-importing the same tag replaces that build's entry (e.g. a cleaner re-read of it). */
    fun importStock(bytes: ByteArray, tag: String) {
        require(bytes.isNotEmpty()) { "Empty file." }
        require(tag.isNotBlank()) { "A starter.cfg can only be added to the library with a confirmed build tag." }
        fileFor(tag).writeBytes(bytes)
    }

    /** "Forget this build's file": it can always be re-pulled or re-extracted. */
    fun clear(tag: String) {
        fileFor(tag).delete()
    }

    companion object {
        /**
         * Parses the SWFL build version out of a PSdZData pool filename - e.g.
         * `swfl_0000271a.bin.007_051_002` -> `007.051.002`, the same dotted form
         * [FlashWrite.readCoreSwflVersion] reports from a live SVK read (see `Svk.Entry.version`'s own
         * doc comment: "the same shape PSdZData pool filenames use after the id"). This is how a
         * container pulled straight from the owner's own PSdZData - never a live car - gets a tag that
         * will actually match a real unit of that build later. Null if the filename carries no
         * recognizable `NNN_NNN_NNN` version suffix - never guessed.
         */
        fun swflVersionFromFilename(name: String): String? {
            val m = Regex("""(\d{3})_(\d{3})_(\d{3})(?:\.[^./]*)?$""").find(name) ?: return null
            return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
        }
    }
}
