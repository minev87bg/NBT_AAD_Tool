package com.nbtaad.editor.addon

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/*
 * Makes the exact pictures the menu look needs from ONE picture of the person's (or one the AI drew): the size of each
 * place, the PNG colour type the original has. Everything here is plain Kotlin on pixels, so it is tested without a
 * phone; only reading a picture file and drawing the AI's shapes need Android (PictureFiles.kt, IconDraw.kt).
 */

/** A picture as pixels: [argb] holds width x height colour ints, alpha first, NOT premultiplied. */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "bad picture size" }
    }
}

/** How a picture goes into a place of another shape: all of it with empty margins, or the place filled and the rest cut off. */
enum class Fit { WHOLE, FILL }

object PictureMaker {
    /** The longest side a picture is read at: bigger ones are made smaller while they are read. */
    const val MAX_SIDE = 1200

    /** The power of two to read a picture of this size by, so its longest side is at most [limit]. */
    fun sampleSize(w: Int, h: Int, limit: Int = MAX_SIDE): Int {
        var s = 1
        while (maxOf(w, h) / s > limit) s *= 2
        return s
    }

    /** Which part of the source goes to which part of the place. */
    class Placement(
        val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
        val dstX: Int, val dstY: Int, val dstW: Int, val dstH: Int,
    )

    /** Where a [sw] x [sh] picture goes in a [dw] x [dh] place. */
    fun place(sw: Int, sh: Int, dw: Int, dh: Int, fit: Fit): Placement {
        if (fit == Fit.WHOLE) {
            val k = minOf(dw.toDouble() / sw, dh.toDouble() / sh)
            val w = (sw * k).let { Math.round(it).toInt() }.coerceIn(1, dw)
            val h = (sh * k).let { Math.round(it).toInt() }.coerceIn(1, dh)
            return Placement(0, 0, sw, sh, (dw - w) / 2, (dh - h) / 2, w, h)
        }
        val k = maxOf(dw.toDouble() / sw, dh.toDouble() / sh)
        val cw = Math.round(dw / k).toInt().coerceIn(1, sw)
        val ch = Math.round(dh / k).toInt().coerceIn(1, sh)
        return Placement((sw - cw) / 2, (sh - ch) / 2, cw, ch, 0, 0, dw, dh)
    }

    /** The picture for a [width] x [height] place: [src] scaled (smoothly, edges kept clean) and put in it. The rest is transparent. */
    fun render(src: Pixels, width: Int, height: Int, fit: Fit): Pixels {
        val p = place(src.width, src.height, width, height, fit)
        val scaled = resize(src, p.srcX, p.srcY, p.srcW, p.srcH, p.dstW, p.dstH)
        val out = IntArray(width * height)
        for (y in 0 until p.dstH) {
            System.arraycopy(scaled, y * p.dstW, out, (p.dstY + y) * width + p.dstX, p.dstW)
        }
        return Pixels(width, height, out)
    }

    /** The PNG for [slot] from [src]. */
    fun make(src: Pixels, slot: MenuLook.Slot, fit: Fit): ByteArray =
        PngWriter.encode(render(src, slot.width, slot.height, fit), slot.colorType)

    // ---------------- scaling ----------------

    /** For each of [m] new pixels along a line of [n]: which old pixels count and how much. A new pixel is an average (shrinking) or a blend of two (growing). */
    private class Taps(val first: IntArray, val weights: Array<FloatArray>)

    private fun taps(n: Int, m: Int): Taps {
        val first = IntArray(m)
        val weights = arrayOfNulls<FloatArray>(m)
        if (m >= n) {
            for (j in 0 until m) {
                val c = (j + 0.5) * n / m - 0.5
                val i0 = Math.floor(c).toInt()
                val t = (c - i0).toFloat()
                val a = i0.coerceIn(0, n - 1)
                val b = (i0 + 1).coerceIn(0, n - 1)
                if (a == b) {
                    first[j] = a; weights[j] = floatArrayOf(1f)
                } else {
                    first[j] = a; weights[j] = floatArrayOf(1f - t, t)
                }
            }
        } else {
            val k = n.toDouble() / m
            for (j in 0 until m) {
                val a = j * k
                val b = (j + 1) * k
                val i0 = Math.floor(a).toInt().coerceIn(0, n - 1)
                val i1 = (Math.ceil(b).toInt() - 1).coerceIn(i0, n - 1)
                val w = FloatArray(i1 - i0 + 1)
                var sum = 0f
                for (i in i0..i1) {
                    val lo = maxOf(a, i.toDouble())
                    val hi = minOf(b, (i + 1).toDouble())
                    w[i - i0] = maxOf(0.0, hi - lo).toFloat()
                    sum += w[i - i0]
                }
                if (sum <= 0f) { w[0] = 1f; sum = 1f }
                for (i in w.indices) w[i] /= sum
                first[j] = i0; weights[j] = w
            }
        }
        return Taps(first, Array(m) { weights[it]!! })
    }

    /**
     * The part [sx], [sy], [sw] x [sh] of [src] as a [dw] x [dh] picture. Colours are mixed with their alpha taken into
     * account, so a transparent edge does not turn dark or light.
     */
    fun resize(src: Pixels, sx: Int, sy: Int, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val tx = taps(sw, dw)
        val ty = taps(sh, dh)
        // pass 1: along x, for every source row; four numbers per pixel (colours times alpha, then alpha)
        val tmp = FloatArray(dw * sh * 4)
        for (y in 0 until sh) {
            val row = (sy + y) * src.width + sx
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                val w = tx.weights[x]
                val f = tx.first[x]
                for (i in w.indices) {
                    val p = src.argb[row + f + i]
                    val al = (p ushr 24) / 255f
                    val wa = w[i] * al
                    r += ((p shr 16) and 0xFF) * wa
                    g += ((p shr 8) and 0xFF) * wa
                    b += (p and 0xFF) * wa
                    a += wa
                }
                val o = (y * dw + x) * 4
                tmp[o] = r; tmp[o + 1] = g; tmp[o + 2] = b; tmp[o + 3] = a
            }
        }
        // pass 2: along y
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val w = ty.weights[y]
            val f = ty.first[y]
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (i in w.indices) {
                    val o = ((f + i) * dw + x) * 4
                    r += tmp[o] * w[i]; g += tmp[o + 1] * w[i]; b += tmp[o + 2] * w[i]; a += tmp[o + 3] * w[i]
                }
                out[y * dw + x] = if (a <= 0.0005f) 0 else {
                    val ia = Math.round(a * 255f).coerceIn(0, 255)
                    (ia shl 24) or (Math.round(r / a).coerceIn(0, 255) shl 16) or
                        (Math.round(g / a).coerceIn(0, 255) shl 8) or Math.round(b / a).coerceIn(0, 255)
                }
            }
        }
        return out
    }

    // ---------------- plain background ----------------

    /** How far apart two colours are: the biggest difference of a red, green or blue value. */
    private fun colorDistance(p: Int, q: Int): Int =
        maxOf(
            Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)),
            Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)),
            Math.abs((p and 0xFF) - (q and 0xFF)),
        )

    /**
     * Makes the plain background of a logo transparent: what has the colour of the picture's corners and touches the
     * edge (the colour may drift by [tolerance] of 255). The border of what stays is softened a little so no light or
     * dark rim is left. Returns null when the corners are not one plain colour, or are transparent already.
     */
    fun withoutBackground(src: Pixels, tolerance: Int = 36): Pixels? {
        val w = src.width
        val h = src.height
        val corners = intArrayOf(src.argb[0], src.argb[w - 1], src.argb[(h - 1) * w], src.argb[h * w - 1])
        if (corners.any { (it ushr 24) < 200 }) return null
        val bg = corners[0]
        if (corners.any { colorDistance(it, bg) > tolerance }) return null

        val gone = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!gone[i] && colorDistance(src.argb[i], bg) <= tolerance) { gone[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        if (tail == 0 || tail == w * h) return null

        val out = src.argb.copyOf()
        val band = tolerance * 3
        for (i in 0 until w * h) {
            if (gone[i]) {
                out[i] = 0
                continue
            }
            val x = i % w
            val y = i / w
            val nearGone = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1]) || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w])
            if (!nearGone) continue
            val d = colorDistance(src.argb[i], bg)
            if (d < band) {
                val a = ((src.argb[i] ushr 24) * (d - tolerance).coerceAtLeast(0) / (band - tolerance).toFloat()).toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (src.argb[i] and 0xFFFFFF)
            }
        }
        return Pixels(w, h, out)
    }
}

/** Writes a PNG (8 bits per channel): RGBA (colour type 6) or grey with alpha (4), the two the menu pictures use. */
object PngWriter {
    fun encode(px: Pixels, colorType: Int): ByteArray {
        val grey = colorType == 4
        val bpp = if (grey) 2 else 4
        val raw = ByteArray((px.width * bpp + 1) * px.height)
        var o = 0
        for (y in 0 until px.height) {
            raw[o++] = 0 // no filter
            for (x in 0 until px.width) {
                val p = px.argb[y * px.width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (grey) {
                    raw[o++] = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
                    raw[o++] = (p ushr 24).toByte()
                } else {
                    raw[o++] = r.toByte(); raw[o++] = g.toByte(); raw[o++] = b.toByte(); raw[o++] = (p ushr 24).toByte()
                }
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val head = ByteArray(13)
        putInt(head, 0, px.width); putInt(head, 4, px.height)
        head[8] = 8; head[9] = (if (grey) 4 else 6).toByte()
        chunk(out, "IHDR", head)
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw)
        d.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        d.end()
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val c = ByteArray(4)
        putInt(c, 0, crc.value.toInt())
        out.write(c)
    }
}
