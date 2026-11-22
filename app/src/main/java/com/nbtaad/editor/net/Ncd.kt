package com.nbtaad.editor.net

/**
 * Motorola S-record codec for BMW NCD coding containers — the file format the head unit's coding is
 * read and written as. Ported from vandelayautollc/nbt-evo-toolkit's `nbtevo/srec.py` (MIT
 * licensed): a coding *datagroup* maps onto an S-record address as `addr = (datagroup << 8) |
 * byteOffset`, so datagroup 0x3001 byte 0x62 lives at address 0x300162.
 *
 * Parsing/emitting is structure-preserving: [emit] re-emits exactly the records [parse] read, with
 * only the bytes you changed in `mem` updated and checksums recomputed. Nothing else moves — so a
 * backup read from the car, re-emitted unchanged, is byte-identical to what the car gave you.
 */
object Ncd {
    sealed class Rec {
        /** A line this codec doesn't model as data (header/terminator S-records, blank lines kept verbatim). */
        data class Raw(val line: String) : Rec()
        /** An S1/S2/S3 data record: type char, address length in bytes, start address, data length in bytes. */
        data class Data(val type: Char, val addrLen: Int, val addr: Int, val dataLen: Int) : Rec()
    }

    class Parsed(val mem: MutableMap<Int, Int>, val recs: List<Rec>)

    fun parse(text: String): Parsed {
        val mem = mutableMapOf<Int, Int>()
        val recs = mutableListOf<Rec>()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.length < 4 || line[0] != 'S') {
                if (line.isNotEmpty()) recs.add(Rec.Raw(line))
                continue
            }
            val t = line[1]
            if (t == '1' || t == '2' || t == '3') {
                val alen = when (t) { '1' -> 2; '2' -> 3; else -> 4 }
                val cnt = line.substring(2, 4).toInt(16)
                val addr = line.substring(4, 4 + alen * 2).toInt(16)
                val dlen = cnt - alen - 1
                val dataStart = 4 + alen * 2
                for (i in 0 until dlen) {
                    val b = line.substring(dataStart + i * 2, dataStart + i * 2 + 2).toInt(16)
                    mem[addr + i] = b
                }
                recs.add(Rec.Data(t, alen, addr, dlen))
            } else {
                recs.add(Rec.Raw(line))
            }
        }
        return Parsed(mem, recs)
    }

    fun emit(mem: Map<Int, Int>, recs: List<Rec>): String {
        val out = StringBuilder()
        for (r in recs) {
            when (r) {
                is Rec.Raw -> { out.append(r.line).append('\n') }
                is Rec.Data -> {
                    val addrBytes = addrToBytes(r.addr, r.addrLen)
                    val data = ByteArray(r.dataLen) { i -> (mem[r.addr + i] ?: 0).toByte() }
                    val body = addrBytes + data
                    val cnt = r.addrLen + r.dataLen + 1
                    var sum = cnt
                    for (b in body) sum += (b.toInt() and 0xFF)
                    val chk = sum.inv() and 0xFF
                    out.append('S').append(r.type)
                    out.append("%02X".format(cnt))
                    for (b in body) out.append("%02X".format(b.toInt() and 0xFF))
                    out.append("%02X".format(chk))
                    out.append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun addrToBytes(addr: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) out[len - 1 - i] = ((addr ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    /** Extracts the contiguous page bytes of each datagroup present in [mem] (offset 0 up to the highest seen). */
    fun pages(mem: Map<Int, Int>, datagroups: Iterable<Int>): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        for (dg in datagroups) {
            val offs = mem.keys.filter { (it ushr 8) == dg }.map { it and 0xFF }
            if (offs.isEmpty()) continue
            val len = offs.max() + 1
            result[dg] = ByteArray(len) { i -> (mem[(dg shl 8) or i] ?: 0).toByte() }
        }
        return result
    }

    /** The same cosmetic header/terminator lines the reference toolkit's own `read_coding.py` uses. */
    const val HEADER_RAW = "S00600004844521B"
    const val TERMINATOR_RAW = "S70500000000FA"

    /**
     * Builds S3 (4-byte address) data records for each datagroup that has any bytes present in
     * [mem] — for turning a freshly-read set of datagroups into a backup NCD, the way
     * `read_coding.py` does, rather than editing records parsed from an existing file.
     */
    fun buildRecords(mem: Map<Int, Int>, datagroups: Iterable<Int>): List<Rec> {
        val recs = mutableListOf<Rec>(Rec.Raw(HEADER_RAW))
        for (dg in datagroups) {
            val offs = mem.keys.filter { (it ushr 8) == dg }.map { it and 0xFF }
            if (offs.isEmpty()) continue
            recs.add(Rec.Data('3', 4, dg shl 8, offs.max() + 1))
        }
        recs.add(Rec.Raw(TERMINATOR_RAW))
        return recs
    }

    fun get(mem: Map<Int, Int>, datagroup: Int, offset: Int): Int = mem[(datagroup shl 8) or offset] ?: 0

    /** Sets bits of one coding byte; `value` is placed as `(value shl shift) and mask`. */
    fun setByte(mem: MutableMap<Int, Int>, datagroup: Int, offset: Int, value: Int, mask: Int = 0xFF, shift: Int = 0) {
        val a = (datagroup shl 8) or offset
        val cur = mem[a] ?: 0
        mem[a] = (cur and mask.inv() and 0xFF) or ((value shl shift) and mask)
    }
}
