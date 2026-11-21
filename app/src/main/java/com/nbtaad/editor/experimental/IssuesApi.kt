package com.nbtaad.editor.experimental

import android.content.Context
import com.nbtaad.editor.install.Limits
import com.nbtaad.editor.install.ReleaseLogic
import com.nbtaad.editor.install.SourceFile
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.Answered
import com.nbtaad.editor.net.Internet
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL

/** Reaches GitHub for the issues and pull requests of the project, and for the files shared in them. No sign-in. */
object IssuesApi {
    private const val BASE = "https://api.github.com/repos/${ReleaseLogic.OWNER}/${ReleaseLogic.REPO}"
    private const val PAGE = 100

    /** What came back, and how many requests GitHub says are left this hour (null when it did not say). */
    class Fetched<T>(val value: T, val remaining: Int?)

    class RateLimited(resetAt: Long?) : Answered(RateLimit.usedUp(resetAt))

    private class Answer(val text: String, val remaining: Int?)

    private fun fetch(ctx: Context, url: String): Answer = Internet.run(ctx) { open ->
        val c = open(URL(url))
        try {
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            val remaining = c.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
            val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
            AppLog.d("GitHub", "GET $url -> $code, $remaining requests left")
            when {
                code == 200 -> Answer(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8), remaining)
                (code == 403 || code == 429) && remaining == 0 -> throw RateLimited(reset)
                code == 403 || code == 429 -> throw IOException("GitHub is limiting requests right now ($code), try again later.")
                code == 404 -> throw IOException("GitHub does not know this page (404).")
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Pages of a list until one is not full (at most [maxPages]). */
    private fun <T> listPages(ctx: Context, url: String, maxPages: Int, parse: (String) -> List<T>): Fetched<List<T>> {
        val all = ArrayList<T>()
        var remaining: Int? = null
        for (n in 1..maxPages) {
            val a = fetch(ctx, "$url${if ('?' in url) "&" else "?"}per_page=$PAGE&page=$n")
            remaining = a.remaining
            val items = parse(a.text)
            all += items
            if (items.size < PAGE) break
        }
        return Fetched(all, remaining)
    }

    /** Issues and pull requests, open and closed, the newest first by [sort] (the last change, or the day opened): up to 300. */
    fun topics(ctx: Context, sort: SortBy = SortBy.ACTIVITY): Fetched<List<Topic>> =
        listPages(ctx, "$BASE/issues?state=all&sort=${sort.api}&direction=desc", 3) { IssueParser.topics(it) }

    /** The comments of one page (up to 300). */
    fun comments(ctx: Context, number: Int): Fetched<List<Comment>> =
        listPages(ctx, "$BASE/issues/$number/comments", 3) { IssueParser.comments(it) }

    /** The newest comments of the whole project, over all pages at once (up to [pages] x 100). */
    fun recentComments(ctx: Context, pages: Int = 3): Fetched<List<Comment>> =
        listPages(ctx, "$BASE/issues/comments?sort=created&direction=desc", pages) { IssueParser.comments(it) }

    /** A text file read into memory: [cut] says that only the start of it is here. */
    class TextFile(val text: String, val cut: Boolean)

    /** Reads a log, note or text that was linked in a page, without saving it. At most [maxBytes] of it. */
    fun text(ctx: Context, link: FileLink, maxBytes: Int = 800 * 1024): TextFile = Internet.run(ctx) { open ->
        val c = open(URL(link.url))
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            val code = c.responseCode
            if (code != 200) {
                throw IOException(
                    "GitHub answered $code for ${link.name}" +
                        if (code == 404 || code == 403) ". It may need a sign-in: open it in the browser instead." else ""
                )
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var cut = false
            c.inputStream.use { input ->
                while (true) {
                    val room = maxBytes - out.size()
                    if (room <= 0) {
                        cut = input.read() >= 0
                        break
                    }
                    val n = input.read(buf, 0, minOf(buf.size, room))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            if (Links.looksLikePage(bytes)) {
                throw IOException("GitHub sent a web page instead of ${link.name} (a sign-in?). Open the link in the browser instead.")
            }
            val text = TextFiles.decode(bytes, cut)
            AppLog.i("GitHub", "Read ${link.name}: ${bytes.size} bytes${if (cut) " (cut)" else ""} from ${link.url}")
            TextFile(text, cut)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Looks at the first bytes of a file that was linked in a page and tells whether it is text that can be read in the
     * app. Only a small piece is asked for, and nothing is saved.
     */
    fun looksReadable(ctx: Context, link: FileLink): Boolean = Internet.run(ctx) { open ->
        val c = open(URL(link.url))
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            c.setRequestProperty("Range", "bytes=0-${TextFiles.SAMPLE - 1}")
            val code = c.responseCode
            if (code == 416) return@run true   // an empty file
            if (code != 200 && code != 206) throw IOException("GitHub answered $code for ${link.name}")
            val buf = ByteArray(TextFiles.SAMPLE)
            var n = 0
            c.inputStream.use { input ->
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val ok = TextFiles.sniff(buf.copyOf(n))
            AppLog.i("GitHub", "Looked into ${link.name}: ${if (ok) "text" else "not text"} (first $n bytes)")
            ok
        } finally {
            c.disconnect()
        }
    }

    /**
     * The README of a GitHub project that a post links, as text, or null when there is none (or it cannot be read). It
     * comes from raw.githubusercontent.com, which does not count against the API's request limit. Nothing is saved.
     */
    fun readme(ctx: Context, owner: String, repo: String, maxBytes: Int = 60 * 1024): String? {
        for (name in listOf("README.md", "readme.md", "README")) {
            val url = "https://raw.githubusercontent.com/$owner/$repo/HEAD/$name"
            val text = try {
                Internet.run(ctx) { open ->
                    val c = open(URL(url))
                    try {
                        c.connectTimeout = 8000
                        c.readTimeout = 20000
                        c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
                        val code = c.responseCode
                        if (code == 404) return@run null
                        if (code != 200) throw IOException("GitHub answered $code for $name")
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var cut = false
                        c.inputStream.use { input ->
                            while (true) {
                                val room = maxBytes - out.size()
                                if (room <= 0) {
                                    cut = input.read() >= 0
                                    break
                                }
                                val n = input.read(buf, 0, minOf(buf.size, room))
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        val bytes = out.toByteArray()
                        if (Links.looksLikePage(bytes)) null else TextFiles.decode(bytes, cut)
                    } finally {
                        c.disconnect()
                    }
                }
            } catch (e: Exception) {
                AppLog.w("GitHub", "No README of $owner/$repo: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (text != null) {
                AppLog.i("GitHub", "Read $name of $owner/$repo: ${text.length} characters")
                return text
            }
        }
        return null
    }

    /** Downloads a file that was linked in a page. A sign-in page instead of the file is reported as such. */
    fun download(ctx: Context, link: FileLink, progress: (Long) -> Unit): SourceFile = Internet.run(ctx) { open ->
        val c = open(URL(link.url))
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            val code = c.responseCode
            if (code != 200) {
                throw IOException(
                    "GitHub answered $code for ${link.name}" +
                        if (code == 404 || code == 403) ". It may need a sign-in: open it in the browser instead." else ""
                )
            }
            if (c.contentLengthLong > Limits.ENTRY) throw IOException("${link.name} is too large (${c.contentLengthLong / 1024 / 1024} MB)")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Limits.ENTRY) throw IOException("${link.name} is unexpectedly large")
                    progress(out.size().toLong())
                }
            }
            val bytes = out.toByteArray()
            if (Links.looksLikePage(bytes)) {
                throw IOException("GitHub sent a web page instead of ${link.name} (a sign-in?). Open the link in the browser instead.")
            }
            AppLog.i("GitHub", "Downloaded ${link.name}: ${bytes.size} bytes from ${link.url}")
            SourceFile(link.name, bytes)
        } finally {
            c.disconnect()
        }
    }
}
