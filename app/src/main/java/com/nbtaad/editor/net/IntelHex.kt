package com.nbtaad.editor.net

/**
 * Minimal Intel HEX decoder. [ContainerExtract] pulls a named payload's bytes out of a SWFL pool file
 * exactly as they sit in the container's data records - and for payloads carried that way (PSdZData's
 * own encoding, not something this toolkit chose), those records hold Intel HEX ASCII text, not the
 * raw file. A live SSH read never needs this - it's already a plain file on the head unit's own
 * filesystem - but a container-extracted payload does, one decode step further.
 *
 * Supports the record types a small single-range file actually uses: `00` (data), `01` (EOF),
 * `02`/`04` (extended segment/linear address); `03`/`05` (start address) are read and ignored, same
 * as every mainstream Intel HEX tool. Refuses rather than silently padding a gap: an address range
 * that isn't contiguous from lowest to highest byte means this wasn't really one flat file, and a bad
 * checksum means a corrupt read - either is reported, never guessed past.
 */
object IntelHex {
    /** Cheap pre-check so a plain (non-hex) payload can just be used as-is: true only if the first
     * non-blank byte is ':', Intel HEX's unambiguous record marker. [decode] is what actually proves
     * or disproves it from there. */
    fun looksLikeIntelHex(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue
            return c == ':'
        }
        return false
    }

    fun decode(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.US_ASCII)
        val mem = sortedMapOf<Int, Int>()
        var extendedBase = 0
        var sawEof = false

        for (rawLine in text.lineSequence()) {
            if (sawEof) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            require(line[0] == ':') { "not an Intel HEX record: \"${line.take(16)}\"" }
            val hexBody = line.substring(1)
            require(hexBody.length >= 10 && hexBody.length % 2 == 0) { "malformed Intel HEX record: \"${line.take(16)}\"" }
            val raw = ByteArray(hexBody.length / 2) { i -> hexBody.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val count = raw[0].toInt() and 0xFF
            require(raw.size == 4 + count + 1) { "Intel HEX record length mismatch: \"${line.take(16)}\"" }
            val sum = raw.copyOfRange(0, raw.size - 1).sumOf { it.toInt() and 0xFF }
            val checksum = raw.last().toInt() and 0xFF
            require(((sum + checksum) and 0xFF) == 0) { "Intel HEX checksum mismatch: \"${line.take(16)}\"" }

            val addr16 = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
            when (val type = raw[3].toInt() and 0xFF) {
                0x00 -> {
                    val base = extendedBase + addr16
                    for (i in 0 until count) mem[base + i] = raw[4 + i].toInt() and 0xFF
                }
                0x01 -> sawEof = true
                0x02 -> extendedBase = (((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)) * 16
                0x04 -> extendedBase = (((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)) shl 16
                0x03, 0x05 -> Unit // start address - informational only
                else -> Unit // unknown record type - ignored, same as every mainstream reader
            }
        }

        require(mem.isNotEmpty()) { "Intel HEX decoded to no data records at all" }
        val lo = mem.firstKey()
        val hi = mem.lastKey()
        val span = hi - lo + 1
        require(mem.size == span) {
            "Intel HEX data isn't one contiguous range (${mem.size} bytes written across a $span-byte span) - not a single flat file"
        }
        return ByteArray(span) { i -> mem.getValue(lo + i).toByte() }
    }
}
