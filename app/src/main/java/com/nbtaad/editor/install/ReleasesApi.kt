package com.nbtaad.editor.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nbtaad.editor.net.Answered
import com.nbtaad.editor.net.Internet
import com.nbtaad.editor.update.UpdateLogic
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL

/** Reaches GitHub for the project's releases, and reads the files the person picks on the phone. */
object ReleasesApi {
    /** The project's releases, newest first. Needs internet. */
    fun list(ctx: Context): List<ReleaseInfo> = listFrom(ctx, ReleaseLogic.LIST_URL)

    /** The releases a GitHub project lists at [listUrl] (api.github.com/repos/OWNER/REPO/releases), newest first. Needs internet. */
    fun listFrom(ctx: Context, listUrl: String): List<ReleaseInfo> {
        val text = getJson(ctx, listUrl)
        val arr = JSONArray(text)
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            out += parseRelease(o)
        }
        return out
    }

    /**
     * One release by its exact tag name (api.github.com/repos/OWNER/REPO/releases/tags/TAG), or null when GitHub has
     * no release under that tag (404) - the project may have renamed or retired it.
     *
     * Why this exists: some projects publish their main build under one tag they keep moving forward (here,
     * Sq00ky's "Installer" - confirmed by cross-referencing the repo's own tags: Installer is the current one,
     * p-installer/previous-installer/previous-p-installer are what it used to point to) instead of a fresh tag per
     * version. GitHub's plain releases list is ordered by when the release was first created, not republished, so a
     * release reused this way can sit far down that order - off the end of a single page - even though it is the
     * newest thing anyone actually published. Fetching it by its known tag name sidesteps that ordering entirely.
     */
    fun releaseByTag(ctx: Context, owner: String, repo: String, tag: String): ReleaseInfo? {
        val text = try {
            getJson(ctx, "https://api.github.com/repos/$owner/$repo/releases/tags/$tag", quiet404 = true)
        } catch (e: NotFound) {
            return null
        }
        val o = JSONObject(text)
        if (o.optBoolean("draft")) return null
        return parseRelease(o)
    }

    // A definite "no" from GitHub (the tag doesn't exist) - not a network problem, so (per Internet.run) it is not
    // retried on another network before this call gives up on it.
    private class NotFound : Answered("404")

    /** [quiet404]: a 404 here is an expected, callable-handled "no" (releaseByTag's tag not existing), not an error
     * to show the person - so it throws the silent [NotFound] instead of [listFrom]'s worded IOException. */
    private fun getJson(ctx: Context, url: String, quiet404: Boolean = false): String = Internet.run(ctx) { open ->
        val c = open(URL(url))
        try {
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                403, 429 -> throw IOException("GitHub is limiting requests right now ($code), try again later.")
                404 -> if (quiet404) throw NotFound() else throw IOException("GitHub does not know that project any more (404)")
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun parseRelease(o: JSONObject): ReleaseInfo {
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
        return ReleaseInfo(
            tag = tag,
            title = o.optString("name").ifBlank { tag },
            prerelease = o.optBoolean("prerelease"),
            published = o.optString("published_at"),
            notes = o.optString("body"),
            pageUrl = o.optString("html_url"),
            assets = list,
        )
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
