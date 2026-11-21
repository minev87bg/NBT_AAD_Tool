package com.nbtaad.editor.experimental

import org.junit.Assert.assertEquals
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
}
