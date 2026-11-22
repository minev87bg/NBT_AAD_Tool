package com.nbtaad.editor.net

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

/**
 * Walks a folder the owner picked (via `OpenDocumentTree`) looking for their own PSdZData
 * `security/` files, by name - so the on-device key recovery in [PsdzKeyRecovery] never needs a
 * PC or a Python script, just one folder-access grant. Pointing this at the small `security/`
 * folder itself (not the whole multi-gigabyte `psdzdata/` tree) is what keeps it quick; it works
 * either way, it's just slower the more of the tree it has to walk.
 *
 * The needed files are often shared packed into an archive rather than loose (a `security.zip`,
 * or the BMW-shipped `resource.jar` that the two logo PNGs normally live inside), so any `.zip` or
 * `.jar` found - anywhere in the picked folder, under any name, nested to any depth - is opened
 * and searched the same way loose files are, not just one hardcoded `resource.jar`.
 */
object PsdzFolderScan {
    data class Found(
        val securityLic: ByteArray,
        val keyreferenceXml: ByteArray,
        val ncdKeysXml: ByteArray?,
        val authL3Xml: ByteArray?,
        val logoPng: ByteArray,
        val miniLogoPng: ByteArray,
    )

    private val WANTED = setOf(
        "sec_security.lic", "sec_keyreference.xml", "sec_ncdkeys.xml", "sec_auth_l3.xml",
        "bmw_logo.png", "bmw_mini_logo.png",
    )

    // Just a sanity cap so pointing this at the wrong (multi-gigabyte) folder or archive by
    // mistake fails fast instead of trying to buffer it all into memory - not a security boundary,
    // this only ever runs against the owner's own local files.
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

    fun scan(ctx: Context, treeUri: Uri): Found {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalArgumentException("couldn't open that folder")

        val byName = HashMap<String, ByteArray>()

        fun consider(leaf: String, bytes: ByteArray) {
            if (leaf in WANTED && leaf !in byName) byName[leaf] = bytes
            if ((leaf.endsWith(".zip") || leaf.endsWith(".jar")) && bytes.size <= MAX_ARCHIVE_BYTES) {
                scanArchive(bytes, ::consider)
            }
        }

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                    continue
                }
                val leaf = (child.name ?: continue).lowercase()
                val relevant = leaf in WANTED || leaf.endsWith(".zip") || leaf.endsWith(".jar")
                if (!relevant || child.length() > MAX_ARCHIVE_BYTES) continue
                val bytes = ctx.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("couldn't read ${child.name}")
                consider(leaf, bytes)
            }
        }
        walk(root)

        val missing = mutableListOf<String>()
        val lic = byName["sec_security.lic"] ?: run { missing.add("sec_security.lic"); null }
        val keyref = byName["sec_keyreference.xml"] ?: run { missing.add("sec_keyreference.xml"); null }
        val ncd = byName["sec_ncdkeys.xml"]
        val l3 = byName["sec_auth_l3.xml"]
        if (ncd == null && l3 == null) missing.add("sec_ncdkeys.xml and/or sec_auth_l3.xml")
        val logo = byName["bmw_logo.png"]
        val miniLogo = byName["bmw_mini_logo.png"]
        if (logo == null || miniLogo == null) missing.add("bmw_logo.png + bmw_mini_logo.png")

        if (missing.isNotEmpty()) {
            throw FileNotFoundException(
                "couldn't find in that folder, loose or inside any .zip/.jar there: ${missing.joinToString(", ")}",
            )
        }
        return Found(lic!!, keyref!!, ncd, l3, logo!!, miniLogo!!)
    }

    private fun scanArchive(zipBytes: ByteArray, consider: (String, ByteArray) -> Unit) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val leaf = entry.name.substringAfterLast('/').lowercase()
                    val relevant = leaf in WANTED || leaf.endsWith(".zip") || leaf.endsWith(".jar")
                    if (relevant) {
                        val bytes = zis.readBytes()
                        if (bytes.size <= MAX_ARCHIVE_BYTES) consider(leaf, bytes)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
