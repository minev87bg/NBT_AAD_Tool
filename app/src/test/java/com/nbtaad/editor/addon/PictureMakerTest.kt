package com.nbtaad.editor.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Making the menu look's pictures from one picture: where it goes, scaling, the plain background, the PNG file. */
class PictureMakerTest {
    private fun solid(w: Int, h: Int, argb: Int) = Pixels(w, h, IntArray(w * h) { argb })
    private val RED = 0xFFFF0000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    @Test
    fun bigPicturesAreReadSmaller() {
        assertEquals(1, PictureMaker.sampleSize(800, 600))
        assertEquals(1, PictureMaker.sampleSize(1200, 100))
        assertEquals(2, PictureMaker.sampleSize(2000, 1500))
        assertEquals(4, PictureMaker.sampleSize(4000, 3000))
        assertEquals(8, PictureMaker.sampleSize(9000, 100))
    }

    @Test
    fun theWholePictureIsCentredWithEmptyMargins() {
        val p = PictureMaker.place(100, 100, 72, 378, Fit.WHOLE)
        assertEquals(72, p.dstW); assertEquals(72, p.dstH)
        assertEquals(0, p.dstX); assertEquals(153, p.dstY)
        assertEquals(100, p.srcW); assertEquals(100, p.srcH)
        val wide = PictureMaker.place(400, 100, 48, 48, Fit.WHOLE)
        assertEquals(48, wide.dstW); assertEquals(12, wide.dstH); assertEquals(18, wide.dstY)
    }

    @Test
    fun fillCutsTheMiddleOutOfThePicture() {
        val p = PictureMaker.place(100, 100, 72, 378, Fit.FILL)
        assertEquals(72, p.dstW); assertEquals(378, p.dstH)
        assertEquals(100, p.srcH)
        assertEquals(19, p.srcW)
        assertEquals(40, p.srcX)
        val same = PictureMaker.place(512, 512, 48, 48, Fit.FILL)
        assertEquals(512, same.srcW); assertEquals(0, same.srcX)
    }

    @Test
    fun aSolidPictureStaysSolidAndTheMarginsStayEmpty() {
        val out = PictureMaker.render(solid(200, 200, RED), 72, 378, Fit.WHOLE)
        assertEquals(72, out.width); assertEquals(378, out.height)
        assertEquals(0, out.argb[0])
        assertEquals(RED, out.argb[189 * 72 + 36])
        assertEquals(RED, out.argb[153 * 72])
        assertEquals(0, out.argb[152 * 72])
        assertEquals(0, out.argb[225 * 72])
        val filled = PictureMaker.render(solid(200, 200, RED), 72, 378, Fit.FILL)
        assertTrue(filled.argb.all { it == RED })
    }

    @Test
    fun shrinkingAveragesAndGrowingBlends() {
        val chess = Pixels(2, 2, intArrayOf(0xFF000000.toInt(), WHITE, WHITE, 0xFF000000.toInt()))
        val one = PictureMaker.resize(chess, 0, 0, 2, 2, 1, 1)
        val v = one[0] and 0xFF
        assertTrue("grey $v", v in 126..129)
        assertEquals(0xFF, one[0] ushr 24)
        val ramp = Pixels(2, 1, intArrayOf(0xFF000000.toInt(), WHITE))
        val big = PictureMaker.resize(ramp, 0, 0, 2, 1, 4, 1)
        val grey = big.map { it and 0xFF }
        assertTrue(grey.toString(), grey[0] <= grey[1] && grey[1] < grey[2] && grey[2] <= grey[3])
    }

    @Test
    fun aTransparentEdgeDoesNotTurnDark() {
        // left half empty (and black underneath), right half opaque white
        val w = 40
        val src = Pixels(w, 4, IntArray(w * 4) { if (it % w < 20) 0x00000000 else WHITE })
        val out = PictureMaker.resize(src, 0, 0, w, 4, 7, 2)
        for (p in out) {
            if ((p ushr 24) > 0) {
                assertEquals("colour of ${p.toUInt().toString(16)}", 0xFFFFFF, p and 0xFFFFFF)
            }
        }
        assertTrue(out.any { (it ushr 24) in 1..254 })
    }

    @Test
    fun theFileIsAPngOfTheRightKindAndReadsBack() {
        val px = Pixels(3, 2, intArrayOf(0xFFFF0000.toInt(), 0x8000FF00.toInt(), 0x000000FF, 0xFFFFFFFF.toInt(), 0xFF123456.toInt(), 0x40FFFF00))
        val bytes = PngWriter.encode(px, 6)
        val info = MenuLook.png(bytes)!!
        assertEquals(3, info.width); assertEquals(2, info.height); assertEquals(6, info.colorType)
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        for (y in 0 until 2) for (x in 0 until 3) {
            val want = px.argb[y * 3 + x]
            val got = img.getRGB(x, y)
            assertEquals("alpha at $x,$y", want ushr 24, got ushr 24)
            if ((want ushr 24) > 0) assertEquals("colour at $x,$y", want and 0xFFFFFF, got and 0xFFFFFF)
        }
    }

    @Test
    fun greyWithAlphaIsWrittenForTheGreyPlaces() {
        val px = Pixels(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0x80FF0000.toInt()))
        val bytes = PngWriter.encode(px, 4)
        assertEquals(4, MenuLook.png(bytes)!!.colorType)
        val r = ImageIO.read(ByteArrayInputStream(bytes)).raster
        assertEquals(255, r.getSample(0, 0, 0)); assertEquals(255, r.getSample(0, 0, 1))
        assertEquals(76, r.getSample(1, 0, 0)); assertEquals(128, r.getSample(1, 0, 1))
    }

    @Test
    fun everyPlaceOfEveryBrandGetsAPictureThatFits() {
        val src = solid(300, 220, 0xFF3366CC.toInt())
        for (brand in MenuLook.BRANDS) for (slot in MenuLook.slots(brand)) for (fit in Fit.values()) {
            val bytes = PictureMaker.make(src, slot, fit)
            assertNull("${slot.rel} $fit", MenuLook.slotProblem(slot, bytes))
            assertNull("${slot.rel} $fit", MenuLook.slotNote(slot, bytes))
        }
    }

    private fun logoOnWhite(): Pixels {
        // a white picture with a red disc, a white hole in the disc, and a light grey rim around the disc
        val n = 60
        val a = IntArray(n * n) { WHITE }
        for (y in 0 until n) for (x in 0 until n) {
            val d = Math.hypot(x - 30.0, y - 30.0)
            if (d <= 22) a[y * n + x] = RED
            if (d <= 8) a[y * n + x] = WHITE
        }
        return Pixels(n, n, a)
    }

    @Test
    fun thePlainBackgroundGoesButAHoleInsideTheLogoStays() {
        val out = PictureMaker.withoutBackground(logoOnWhite())
        assertNotNull(out)
        out!!
        assertEquals(0, out.argb[0] ushr 24)
        assertEquals(0, out.argb[59 * 60 + 59] ushr 24)
        assertEquals(RED, out.argb[30 * 60 + 12])
        assertEquals(WHITE, out.argb[30 * 60 + 30]) // the enclosed white is part of the logo
    }

    @Test
    fun noPlainBackgroundMeansNothingIsChanged() {
        val busy = solid(10, 10, WHITE)
        busy.argb[0] = RED
        assertNull(PictureMaker.withoutBackground(busy))
        assertNull(PictureMaker.withoutBackground(solid(10, 10, 0x00FFFFFF)))
        assertNull(PictureMaker.withoutBackground(solid(10, 10, WHITE)))
    }
}
