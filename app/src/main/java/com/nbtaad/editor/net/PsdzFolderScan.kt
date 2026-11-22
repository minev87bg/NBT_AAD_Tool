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
        "resource.jar", "bmw_logo.png", "bmw_mini_logo.png",
    )

    fun scan(ctx: Context, treeUri: Uri): Found {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalArgumentException("couldn't open that folder")

        val byName = HashMap<String, DocumentFile>()
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    val leaf = (child.name ?: continue).lowercase()
                    if (leaf in WANTED && leaf !in byName) byName[leaf] = child
                }
            }
        }
        walk(root)

        fun readBytes(doc: DocumentFile): ByteArray =
            ctx.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("couldn't read ${doc.name}")

        val missing = mutableListOf<String>()
        val lic = byName["sec_security.lic"]?.let(::readBytes) ?: run { missing.add("sec_security.lic"); null }
        val keyref = byName["sec_keyreference.xml"]?.let(::readBytes) ?: run { missing.add("sec_keyreference.xml"); null }
        val ncd = byName["sec_ncdkeys.xml"]?.let(::readBytes)
        val l3 = byName["sec_auth_l3.xml"]?.let(::readBytes)
        if (ncd == null && l3 == null) missing.add("sec_ncdkeys.xml and/or sec_auth_l3.xml")

        var logo = byName["bmw_logo.png"]?.let(::readBytes)
        var miniLogo = byName["bmw_mini_logo.png"]?.let(::readBytes)
        if (logo == null || miniLogo == null) {
            byName["resource.jar"]?.let(::readBytes)?.let { jarBytes ->
                val (l, m) = extractLogos(jarBytes)
                logo = l
                miniLogo = m
            }
        }
        if (logo == null || miniLogo == null) {
            missing.add("resource.jar (or bmw_logo.png + bmw_mini_logo.png)")
        }

        if (missing.isNotEmpty()) {
            throw FileNotFoundException("couldn't find in that folder: ${missing.joinToString(", ")}")
        }
        return Found(lic!!, keyref!!, ncd, l3, logo!!, miniLogo!!)
    }

    private fun extractLogos(jarBytes: ByteArray): Pair<ByteArray, ByteArray> {
        var logo: ByteArray? = null
        var miniLogo: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val leaf = entry.name.substringAfterLast('/')
                when {
                    leaf.equals("bmw_logo.png", ignoreCase = true) -> logo = zis.readBytes()
                    leaf.equals("bmw_mini_logo.png", ignoreCase = true) -> miniLogo = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        if (logo == null || miniLogo == null) {
            throw FileNotFoundException("bmw_logo.png / bmw_mini_logo.png not found inside resource.jar")
        }
        return logo!! to miniLogo!!
    }
}
