package com.nbtaad.editor.net

/**
 * Decodes the head unit's SVK ("software version", UDS `22 F1 01`) - the list of every firmware/coding
 * area fitted and its version. Ported from vandelayautollc/nbt-evo-toolkit's `nbtevo/uds.py`
 * (`decode_svk`/`decode_sgbm`, MIT licensed). Used before a flash to confirm the bootloader (BTLD) id
 * matches what the car's own L3 key is bound to - "read the SVK first" is this project's own stated
 * rule (its `AGENTS.md`: "Almost everything keys off these two ids ... When in doubt, read the SVK
 * first"), not a shortcut taken here.
 */
object Svk {
    /** One entry: a process class (e.g. "BTLD", "CAFD"), its id, and a main.sub.patch version. */
    data class Entry(val processClass: String, val classCode: Int, val id: Long, val main: Int, val sub: Int, val patch: Int) {
        val idHex: String get() = "%08X".format(id)

        /** "main.sub.patch", the same shape PSdZData pool filenames use after the id
         * (e.g. `swfl_0000271a.bin.007_051_002` -> `007.051.002`). */
        val version: String get() = "%03d.%03d.%03d".format(main, sub, patch)
    }

    data class Svk(val svkVersion: Int, val progDepChecked: Int, val entries: List<Entry>) {
        /** The lowest-sorted BTLD entry, same tie-break as the reference project's `Svk.bootloader`
         * property (there should only ever be one, but this matches it exactly rather than assuming). */
        val bootloader: Entry? get() = entries.filter { it.processClass == "BTLD" }
            .minWithOrNull(compareBy({ it.id }, { it.main }, { it.sub }, { it.patch }))

        /** First entry matching [processClass] and [id] exactly - e.g. the core SWFL build a captured
         * `starter.cfg` is tagged with (see [FlashWrite.readCoreSwflVersion]). Null if this car's SVK
         * doesn't carry that id (nothing is guessed; the caller treats the file as untagged). */
        fun entry(processClass: String, id: Long): Entry? =
            entries.firstOrNull { it.processClass == processClass && it.id == id }
    }

    private val PROCESS_CLASS = mapOf(
        1 to "HWEL", 2 to "HWAP", 3 to "HWFR", 4 to "GWTB", 5 to "CAFD", 6 to "BTLD", 7 to "FLSL",
        8 to "SWFL", 9 to "SWFF", 10 to "SWPF", 11 to "ONPS", 12 to "IBAD", 13 to "SWFK", 15 to "FAFP",
        16 to "FCFA", 26 to "TLRT", 27 to "TPRG", 28 to "BLUP", 29 to "FLUP", 160 to "ENTD", 161 to "NAVD",
        162 to "FCFN", 192 to "SWUP", 193 to "SWIP",
    )

    private fun u32be(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun decodeEntry(b: ByteArray, at: Int): Entry {
        val code = b[at].toInt() and 0xFF
        return Entry(
            processClass = PROCESS_CLASS[code] ?: "UNKNOWN",
            classCode = code,
            id = u32be(b, at + 1),
            main = b[at + 5].toInt() and 0xFF,
            sub = b[at + 6].toInt() and 0xFF,
            patch = b[at + 7].toInt() and 0xFF,
        )
    }

    /**
     * Decodes the raw SVK value - the bytes *after* `62 F1 01` in the read response. Layout:
     * `[svkVersion u8][progDepChecked u8][count u16 BE][fingerprint 4|13 bytes][N×8-byte entry]`.
     * The fingerprint's own length varies, so (matching the reference project exactly) entries are
     * taken from the LAST `8*count` bytes rather than computed from a fixed fingerprint offset.
     * Returns null rather than throwing on a too-short or inconsistent value - this gates a flash,
     * so an unreadable SVK must read as "don't know", never as a guess.
     */
    fun decode(value: ByteArray): Svk? {
        if (value.size < 4) return null
        val count = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        val need = count * 8
        if (4 + need > value.size) return null
        val first = value.size - need
        val entries = (0 until count).map { decodeEntry(value, first + it * 8) }
        return Svk(value[0].toInt() and 0xFF, value[1].toInt() and 0xFF, entries)
    }
}
