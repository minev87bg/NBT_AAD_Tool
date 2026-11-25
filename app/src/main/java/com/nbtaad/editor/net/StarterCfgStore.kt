package com.nbtaad.editor.net

import android.content.Context
import java.io.File

/**
 * Stores the owner's own stock `/opt/sys/etc/starter.cfg` - imported once (pulled off the car over
 * SSH, or extracted from the owner's own SWFL `0000271A` firmware image with the reference project's
 * `container.py extract_payload`), never invented here. This is the one fact the OPT_DEBUG flash
 * needs that genuinely can't be guessed: which package entry's `RequestState` is `STOP` vs `RUN`, and
 * what the surrounding file actually looks like on this software version. See [StarterCfgPatch].
 *
 * Unlike [CodingKeyStore], this isn't a secret - it's a QNX system config file, not a signing key - so
 * it's kept in plain app-private storage, not Android-Keystore-encrypted. It's still precious, though:
 * it's the only safe "stock" to revert to, so it's never overwritten once imported (re-importing asks
 * first - see the UI).
 */
class StarterCfgStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "starter_cfg").apply { mkdirs() }
    private val stockFile = File(dir, "starter.cfg.stock")
    private val tagFile = File(dir, "starter.cfg.tag")

    fun hasStockFile(): Boolean = stockFile.exists()

    /** [tag] is the core SWFL version ([FlashWrite.readCoreSwflVersion]) this file was read alongside,
     * if known - stored purely as a label next to the bytes; nothing here keys off it. */
    fun importStock(bytes: ByteArray, tag: String? = null) {
        require(bytes.isNotEmpty()) { "Empty file." }
        stockFile.writeBytes(bytes)
        if (tag != null) tagFile.writeText(tag) else tagFile.delete()
    }

    fun readStock(): ByteArray? = stockFile.takeIf { it.exists() }?.readBytes()
    fun readTag(): String? = tagFile.takeIf { it.exists() }?.readText()

    /** "Forget this file": the owner can always re-pull or re-extract it. */
    fun clear() {
        stockFile.delete()
        tagFile.delete()
    }
}
