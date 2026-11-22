package com.nbtaad.editor.net

import java.math.BigInteger

/**
 * The little-endian, raw-RSA-e=3 primitives BMW's `BMWSigVerify` scheme is built on — shared by the
 * coding signature ([NcdSigning]) and the L3 SecurityAccess handshake ([L3]). Ported from
 * vandelayautollc/nbt-evo-toolkit's `nbtevo/l3.py` and `02-signing-model.md` (MIT licensed): the
 * numbers below (little-endian digests, the per-word byte swap) are BMW's own wire format, not a
 * guess, and the same oddities appear in both places that use this primitive.
 */
internal object RsaE3 {
    /** Byte-reverses each 32-bit word, keeping word order. BMW's own big-number storage quirk. */
    fun wordSwap(b: ByteArray): ByteArray {
        require(b.size % 4 == 0) { "length must be a multiple of 4, was ${b.size}" }
        val out = ByteArray(b.size)
        var i = 0
        while (i < b.size) {
            out[i] = b[i + 3]; out[i + 1] = b[i + 2]; out[i + 2] = b[i + 1]; out[i + 3] = b[i]
            i += 4
        }
        return out
    }

    /** Little-endian bytes, read as unsigned, to a non-negative BigInteger (Python's `int.from_bytes(b, "little")`). */
    fun leToBigInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    /**
     * A non-negative BigInteger to exactly [size] little-endian bytes, zero-padded at the high end
     * (Python's `v.to_bytes(size, "little")`). Throws if [v] doesn't fit — callers that might see an
     * out-of-range value (e.g. a signature check on bad data) must test [BigInteger.bitLength] first
     * rather than rely on this to fail safely; it does not.
     */
    fun bigIntToLe(v: BigInteger, size: Int): ByteArray {
        require(v.signum() >= 0) { "value must be non-negative" }
        var be = v.toByteArray() // big-endian two's complement; may carry one leading 0x00
        if (be.size > 1 && be[0] == 0.toByte()) be = be.copyOfRange(1, be.size)
        require(be.size <= size) { "value needs ${be.size} bytes, does not fit in $size" }
        val out = ByteArray(size)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }
}
