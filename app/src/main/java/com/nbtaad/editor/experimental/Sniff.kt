package com.nbtaad.editor.experimental

/** Tells what a downloaded thing is from its first bytes, for the log (no Android parts, so it can be tested). */
object Sniff {
    /** What something that is not a picture the phone can show is: usually a video, which GitHub serves at the same kind of address. */
    fun nonPicture(b: ByteArray): String {
        fun at(o: Int, s: String) = b.size >= o + s.length && s.indices.all { b[o + it] == s[it].code.toByte() }
        return when {
            at(4, "ftyp") -> "a video or an image of the ${String(b, 8, minOf(4, b.size - 8), Charsets.ISO_8859_1).trim()} family"
            b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "a WebM/MKV video"
            at(0, "RIFF") && at(8, "AVI ") -> "an AVI video"
            at(0, "<") -> "text or a web page"
            b.isEmpty() -> "empty"
            else -> "starts with " + b.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
