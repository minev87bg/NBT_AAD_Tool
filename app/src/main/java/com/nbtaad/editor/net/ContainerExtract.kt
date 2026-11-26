package com.nbtaad.editor.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Locates and extracts one named payload (by its descriptor's `Name=` or `TargetPath` basename) out
 * of a WOODOO/IOC-BOLO scripted-flash container - the same format BMW/Harman-Becker ships NBT EVO's
 * own `swfl_*`/`btld_*`/`ibad_*` PSdZData pool files in, and the format [FlashContainer] already
 * writes the *small* flashable side of. This is the read-side counterpart, ported from
 * vandelayautollc/nbt-evo-toolkit's `capabilities/flash_and_patching/tools/container.py`
 * (`extract_payload`/`_group_iter`, MIT licensed) - the toolkit's own documented alternative to a live
 * SSH read: "pulled off the car over SSH, or extracted from the owner's own SWFL firmware image."
 *
 * Grammar (identical on every record, no padding/alignment): `marker:u8, index:u16 LE, length:u32 LE,
 * segment:u8, flags:u8`, then `length` body bytes, chained to EOF. Marker `0x02` is a descriptor
 * (ASCII `Key=Val;...` metadata body); any other marker is a binary data record belonging to the most
 * recent descriptor's group - the toolkit's own parser treats `segment`'s count as informational only
 * and relies purely on header lengths walking forward, which is what this does too. These images run
 * to several hundred MB (the `0000271A` SWFL pool file is ~942 MB per docs/psdzdata-manifest.md), so
 * this streams [InputStream] forward exactly once - never buffering more than one record's body, and
 * never backward-seeking - rather than loading a whole image into memory on a phone.
 */
object ContainerExtract {
    private const val MARK_DESC = 0x02
    private const val HEADER_LEN = 9
    private const val MAX_META_BYTES = 1L shl 20 // guards a corrupt/huge descriptor length, like the Python port
    private const val COPY_CHUNK = 1 shl 16

    sealed class Result {
        data class Found(val bytes: ByteArray) : Result()
        object NotFound : Result()
    }

    /** Reads [input] forward exactly once, to EOF or until every data record belonging to the first
     * descriptor named [name] has been captured. [input] is consumed; the caller owns closing it. */
    fun extract(input: InputStream, name: String): Result {
        val stream = input.buffered(1 shl 20)
        val header = ByteArray(HEADER_LEN)
        var wantedIndex: Int? = null
        var captured: ByteArrayOutputStream? = null

        while (readHeader(stream, header)) {
            val marker = header[0].toInt() and 0xFF
            val index = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
            val length = u32le(header, 3)
            // byte7 (segment count/ordinal), byte8 (flags, always 0) - informational only, per the
            // reference parser's own doc comment; chaining relies purely on lengths, not on these.

            if (marker == MARK_DESC) {
                // A new group starts: if the previous one was our match, it's now complete - nothing
                // later can still belong to it (groups are contiguous).
                captured?.let { return Result.Found(it.toByteArray()) }
                if (length > MAX_META_BYTES) throw IOException("descriptor body implausibly large ($length bytes) - not a real container")
                val meta = parseMeta(readExactly(stream, length.toInt()))
                val base = meta["Name"] ?: meta["TargetPath"]?.trimEnd('/')?.substringAfterLast('/')
                if (base == name) {
                    wantedIndex = index
                    captured = ByteArrayOutputStream()
                } else {
                    wantedIndex = null
                }
            } else if (wantedIndex != null && index == wantedIndex) {
                copyExactly(stream, length, captured!!)
            } else {
                skipExactly(stream, length)
            }
        }
        return captured?.let { Result.Found(it.toByteArray()) } ?: Result.NotFound
    }

    private fun u32le(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    /** True if a full 9-byte header was read; false only on a clean EOF right at a record boundary. */
    private fun readHeader(input: InputStream, into: ByteArray): Boolean {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("truncated record header")
            }
            off += n
        }
        return true
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) throw EOFException("truncated descriptor body")
            off += n
        }
        return out
    }

    private fun copyExactly(input: InputStream, len: Long, into: ByteArrayOutputStream) {
        var rem = len
        val buf = ByteArray(COPY_CHUNK)
        while (rem > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n < 0) throw EOFException("truncated data record body")
            into.write(buf, 0, n)
            rem -= n
        }
    }

    private fun skipExactly(input: InputStream, len: Long) {
        var rem = len
        val buf = ByteArray(COPY_CHUNK)
        while (rem > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n < 0) throw EOFException("truncated record body while skipping")
            rem -= n
        }
    }

    /** `Key=Val;Key=Val;` ASCII, tolerant of trailing/empty segments - mirrors the reference parser's
     * own `_parse_metadata` (latin-1, first value wins on a repeated key). */
    private fun parseMeta(raw: ByteArray): Map<String, String> {
        val text = String(raw, Charsets.ISO_8859_1)
        val map = LinkedHashMap<String, String>()
        for (part in text.split(';')) {
            if (part.isBlank()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq)
            if (k !in map) map[k] = part.substring(eq + 1)
        }
        return map
    }
}
