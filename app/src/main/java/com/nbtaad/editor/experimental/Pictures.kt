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
                if (bmp == null) AppLog.w("Pictures", "Not a picture the phone can show (${bytes.size} bytes, ${Sniff.nonPicture(bytes)}): $url")
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
