package com.nbtaad.editor.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nbtaad.editor.net.Internet
import com.nbtaad.editor.update.UpdateLogic
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL

/** Reaches GitHub for the project's releases, and reads the files the person picks on the phone. */
object ReleasesApi {
    /** The project's releases, newest first. Needs internet. */
    fun list(ctx: Context): List<ReleaseInfo> = listFrom(ctx, ReleaseLogic.LIST_URL)

    /** The releases a GitHub project lists at [listUrl] (api.github.com/repos/OWNER/REPO/releases), newest first. Needs internet. */
    fun listFrom(ctx: Context, listUrl: String): List<ReleaseInfo> {
        val text = Internet.run(ctx) { open ->
            val c = open(URL(listUrl))
            try {
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                when (val code = c.responseCode) {
                    200 -> c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    403, 429 -> throw IOException("GitHub is limiting requests right now ($code), try again later.")
                    404 -> throw IOException("GitHub does not know that project any more (404)")
                    else -> throw IOException("GitHub answered $code")
                }
            } finally {
                c.disconnect()
            }
        }
        val arr = JSONArray(text)
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            val tag = o.optString("tag_name")
            val assets = o.optJSONArray("assets")
            val list = ArrayList<AssetInfo>()
            for (j in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(j)
                list += AssetInfo(
                    name = a.optString("name"),
                    size = a.optLong("size", -1L),
                    url = a.optString("browser_download_url"),
                    sha256 = UpdateLogic.parseDigest(a.optString("digest", "")),
                )
            }
            out += ReleaseInfo(
                tag = tag,
                title = o.optString("name").ifBlank { tag },
                prerelease = o.optBoolean("prerelease"),
                published = o.optString("published_at"),
                notes = o.optString("body"),
                pageUrl = o.optString("html_url"),
                assets = list,
            )
        }
        return out
    }

    /** Downloads one release file into memory and checks its size and checksum when GitHub states them. */
    fun download(ctx: Context, a: AssetInfo, progress: (Long) -> Unit): ByteArray =
        Internet.run(ctx) { open ->
            val c = open(URL(a.url))
            try {
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
                if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode} for ${a.name}")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                c.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > Limits.ENTRY) throw IOException("${a.name} is unexpectedly large")
                        progress(out.size().toLong())
                    }
                }
                val bytes = out.toByteArray()
                if (a.size > 0 && bytes.size.toLong() != a.size) {
                    throw IOException("${a.name} arrived incomplete (${bytes.size} of ${a.size} bytes)")
                }
                if (a.sha256 != null && UpdateLogic.sha256Hex(bytes) != a.sha256) {
                    throw IOException("${a.name} does not match the checksum GitHub lists for it")
                }
                bytes
            } finally {
                c.disconnect()
            }
        }

    /** Reads the files the person picked (each as it is named in the file picker). */
    fun readPicked(ctx: Context, uris: List<Uri>): List<SourceFile> {
        val files = ArrayList<SourceFile>()
        var total = 0L
        for (u in uris) {
            var name = u.lastPathSegment ?: "file"
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val out = ByteArrayOutputStream()
            ctx.contentResolver.openInputStream(u)?.use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (out.size() > Limits.ENTRY || total > Limits.TOTAL) throw IOException("$name is too large to be part of a build")
                }
            } ?: throw IOException("Could not open $name")
            files += SourceFile(name, out.toByteArray())
        }
        return files
    }
}
