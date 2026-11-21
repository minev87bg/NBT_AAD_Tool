package com.nbtaad.editor.experimental

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.Internet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

/**
 * Pictures from the pages, loaded into memory only (nothing is saved on the phone). A few at a time, each decoded at
 * the size it is needed (a small one for the grid, a large one for the gallery) and kept while the memory allows.
 */
object Pictures {
    private const val MAX_BYTES = 20 * 1024 * 1024

    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)
    private val bitmaps = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val raw = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val gate = Semaphore(3)

    /** Addresses that turned out to be videos (GitHub serves a video attached to a post like a picture). */
    private val videos = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isVideo(url: String): Boolean = url in videos

    private const val MAX_VIDEO = 100L * 1024 * 1024
    private const val KEEP_VIDEOS = 3

    private fun key(url: String, maxSide: Int) = "$url#$maxSide"

    fun cached(url: String, maxSide: Int): Bitmap? = bitmaps.get(key(url, maxSide))

    /** The picture at [url] with its longer side at most about [maxSide] pixels; null when it cannot be shown. */
    suspend fun load(ctx: Context, url: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(url, maxSide)
        bitmaps.get(k)?.let { return@withContext it }
        gate.withPermit {
            bitmaps.get(k)?.let { return@withPermit it }
            try {
                val bytes = raw.get(url) ?: download(ctx, url).also { raw.put(url, it) }
                val bmp = decode(bytes, maxSide)
                if (bmp == null && Sniff.videoExtension(bytes) != null) {
                    if (videos.add(url)) AppLog.i("Pictures", "A video, not a picture (${bytes.size} bytes, ${Sniff.nonPicture(bytes)}): $url")
                } else if (bmp == null) {
                    AppLog.w("Pictures", "Not a picture the phone can show (${bytes.size} bytes, ${Sniff.nonPicture(bytes)}): $url")
                }
                if (bmp != null) bitmaps.put(k, bmp)
                bmp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Pictures", "Could not load $url: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * A video from a page, as a file in the app's cache folder (the phone's player needs a file). The most recent
     * [KEEP_VIDEOS] are kept there until Android clears the cache; [progress] gets the bytes loaded so far.
     */
    suspend fun videoFile(ctx: Context, url: String, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "video").apply { mkdirs() }
        val name = Sniff.videoName(url)
        dir.listFiles()?.firstOrNull { it.name.startsWith("$name.") && it.length() > 0 }?.let {
            it.setLastModified(System.currentTimeMillis())
            return@withContext it
        }
        val tmp = File(dir, "$name.part")
        try {
            val have = raw.get(url)
            if (have != null) tmp.writeBytes(have) else downloadTo(ctx, url, tmp, progress)
            val head = ByteArray(16)
            val n = tmp.inputStream().use { it.read(head) }
            val ext = Sniff.videoExtension(head.copyOf(maxOf(n, 0))) ?: throw IOException("This is not a video the phone can play")
            val out = File(dir, "$name.$ext")
            if (!tmp.renameTo(out)) throw IOException("Could not keep the video on the phone")
            dir.listFiles()?.filter { it.name != out.name }?.sortedByDescending { it.lastModified() }?.drop(KEEP_VIDEOS - 1)?.forEach { it.delete() }
            AppLog.i("Pictures", "Video ready to play: ${out.length() / 1024} KB, ${ext}: $url")
            out
        } finally {
            tmp.delete()
        }
    }

    private fun downloadTo(ctx: Context, url: String, file: File, progress: (Long) -> Unit) = Internet.run(ctx) { open ->
        val c = open(URL(url))
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            val code = c.responseCode
            if (code != 200) throw IOException("GitHub answered $code")
            if (c.contentLengthLong > MAX_VIDEO) throw IOException("The video is too large (over ${MAX_VIDEO / 1024 / 1024} MB)")
            var total = 0L
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > MAX_VIDEO) throw IOException("The video is too large (over ${MAX_VIDEO / 1024 / 1024} MB)")
                        progress(total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun download(ctx: Context, url: String): ByteArray = Internet.run(ctx) { open ->
        val c = open(URL(url))
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "NBT-AAD-Tool")
            val code = c.responseCode
            if (code != 200) throw IOException("GitHub answered $code")
            if (c.contentLengthLong > MAX_BYTES) throw IOException("The picture is too large")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("The picture is too large")
                }
            }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
