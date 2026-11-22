package com.nbtaad.editor.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import com.nbtaad.editor.addon.IconSpec
import com.nbtaad.editor.addon.PictureMaker
import com.nbtaad.editor.addon.Pixels
import java.io.ByteArrayInputStream
import java.io.IOException

/** The parts of "make from picture" that need Android: reading a picture file, and painting the AI's drawing. */
object PictureFiles {
    /** A picture file bigger than this is not read (photos are often 5 to 10 MB). */
    const val MAX_FILE = 30L * 1024 * 1024

    /** Reads the picture the person picked, at most [PictureMaker.MAX_SIDE] long, turned the way it was taken. */
    fun read(ctx: Context, uri: Uri): Pixels {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE) throw IOException("that picture is bigger than ${MAX_FILE / 1024 / 1024} MB")
            }
            out.toByteArray()
        } ?: throw IOException("cannot open it")
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Pixels {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("that is not a picture the phone can read (use PNG, JPEG or WebP)")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = PictureMaker.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("the phone could not decode that picture")
        val turn = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (turn != 0f) {
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn) }, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Pixels(w, h, argb)
    }

    /** Paints a drawing of the AI on a transparent [size] x [size] sheet. */
    fun draw(spec: IconSpec, size: Int = 512): Pixels {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = size / IconSpec.SHEET
        c.scale(k, k)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (s in spec.shapes) {
            val passes = listOfNotNull(s.fill?.let { it to Paint.Style.FILL }, s.stroke?.let { it to Paint.Style.STROKE })
            for ((col, style) in passes) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = col
                paint.style = style
                paint.strokeWidth = s.width
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                fun n(key: String) = s.n[key] ?: 0f
                when (s.type) {
                    "rect" -> c.drawRect(n("x"), n("y"), n("x") + n("w"), n("y") + n("h"), paint)
                    "round" -> c.drawRoundRect(RectF(n("x"), n("y"), n("x") + n("w"), n("y") + n("h")), n("r"), n("r"), paint)
                    "circle" -> c.drawCircle(n("cx"), n("cy"), n("r"), paint)
                    "ellipse" -> c.drawOval(RectF(n("cx") - n("rx"), n("cy") - n("ry"), n("cx") + n("rx"), n("cy") + n("ry")), paint)
                    "line" -> c.drawLine(n("x1"), n("y1"), n("x2"), n("y2"), paint)
                    "arc" -> {
                        var sweep = n("to") - n("from")
                        if (sweep <= 0f) sweep += 360f
                        c.drawArc(RectF(n("cx") - n("r"), n("cy") - n("r"), n("cx") + n("r"), n("cy") + n("r")), n("from"), sweep, false, paint)
                    }
                    "poly" -> {
                        val path = Path()
                        s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second) }
                        if (s.points.size >= 3) path.close()
                        c.drawPath(path, paint)
                    }
                }
            }
        }
        val argb = IntArray(size * size)
        bmp.getPixels(argb, 0, size, 0, 0, size, size)
        bmp.recycle()
        return Pixels(size, size, argb)
    }
}
