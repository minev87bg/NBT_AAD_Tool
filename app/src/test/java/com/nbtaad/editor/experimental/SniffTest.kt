package com.nbtaad.editor.experimental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun anMp4IsToldFromItsFtypBox() {
        val mp4 = bytes(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(8)
        assertTrue(Sniff.nonPicture(mp4).contains("isom"))
        assertTrue(Sniff.nonPicture(mp4).contains("video"))
    }

    @Test
    fun otherThingsAreNamedOrShownAsBytes() {
        assertEquals("a WebM/MKV video", Sniff.nonPicture(bytes(0x1A, 0x45, 0xDF, 0xA3, 1, 2)))
        assertEquals("text or a web page", Sniff.nonPicture("<html>".toByteArray()))
        assertEquals("empty", Sniff.nonPicture(ByteArray(0)))
        assertEquals("starts with 01 02 03", Sniff.nonPicture(bytes(1, 2, 3)))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ftyp(brand: String) = bytes(0, 0, 0, 0x20) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(8)

    @Test
    fun aVideoIsToldFromItsFirstBytes() {
        assertEquals("mp4", Sniff.videoExtension(ftyp("mp42")))
        assertEquals("mp4", Sniff.videoExtension(ftyp("isom")))
        assertEquals("mp4", Sniff.videoExtension(ftyp("qt  ")))
        assertEquals("webm", Sniff.videoExtension(bytes(0x1A, 0x45, 0xDF, 0xA3, 1, 2, 3, 4)))
        assertEquals("avi", Sniff.videoExtension("RIFF".toByteArray() + ByteArray(4) + "AVI LIST".toByteArray()))
    }

    @Test
    fun picturesAndOtherThingsAreNotVideos() {
        assertNull(Sniff.videoExtension(ftyp("heic")))
        assertNull(Sniff.videoExtension(ftyp("avif")))
        assertNull(Sniff.videoExtension(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertNull(Sniff.videoExtension("<html>".toByteArray()))
        assertNull(Sniff.videoExtension(ByteArray(0)))
        assertNull(Sniff.videoExtension("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray()))
    }

    @Test
    fun theSavedNameOfAVideoIsStableAndShort() {
        val a = Sniff.videoName("https://github.com/user-attachments/assets/aaa")
        assertEquals(16, a.length)
        assertEquals(a, Sniff.videoName("https://github.com/user-attachments/assets/aaa"))
        assertNotEquals(a, Sniff.videoName("https://github.com/user-attachments/assets/bbb"))
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
