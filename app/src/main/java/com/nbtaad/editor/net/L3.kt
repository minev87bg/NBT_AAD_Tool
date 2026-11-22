package com.nbtaad.editor.net

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The shared BMW "L3" SecurityAccess handshake: turns a `27 01`/`27 11` requestSeed response into
 * the `27 02`/`27 12` sendKey payload that unlocks a coding write or a flash. Same construction,
 * same key, either action.
 *
 * Ported from vandelayautollc/nbt-evo-toolkit's `nbtevo/l3.py` (MIT licensed), proven on that
 * project's own car. Ships no key: [Key] holds only what the *owner's own* recovered `l3_key.json`
 * (via that toolkit's `nbtevo.keys --kind l3`, run once against their own PSdZData) provides.
 */
object L3 {
    class Key(modulusHex: String, privateExponentHex: String, publicExponent: Int = 3) {
        val modulus: BigInteger = BigInteger(modulusHex, 16)
        val privateExponent: BigInteger = BigInteger(privateExponentHex, 16)
        val publicExponent: BigInteger = BigInteger.valueOf(publicExponent.toLong())
        /** The modulus's own size in bytes — the wire length `signSeed` uses unless told otherwise. */
        val nBytes: Int = (modulus.bitLength() + 7) / 8
    }

    /**
     * Builds the sendKey payload (the bytes after `27 02`/`27 12`) from the 8-byte seed in the
     * requestSeed response.
     *
     * `block = UserID(4 BE) || Controller(4 BE) || seed(8)`; `digest = MD5(block)` read
     * little-endian; `s = digest^d mod N` (raw RSA, e=3, no padding); the wire payload is a
     * big-endian word-count prefix (`modLen/4`) followed by `s`, little-endian, word-byte-swapped.
     *
     * Self-checks `s^e mod N == digest` before returning — exactly what the toolkit's own signer
     * does — and throws if it doesn't, because that means the key is wrong, not that the car will
     * reject it: never send a key that failed its own self-check.
     */
    fun signSeed(seed: ByteArray, key: Key, btld: Int = 0x00001FFD, userId: Int = 0x00000000, modLen: Int = key.nBytes): ByteArray {
        require(seed.size == 8) { "seed must be 8 bytes, got ${seed.size}" }
        val block = ByteArray(16)
        writeBe32(block, 0, userId)
        writeBe32(block, 4, btld)
        seed.copyInto(block, 8)

        val digest = MessageDigest.getInstance("MD5").digest(block)
        val m = RsaE3.leToBigInt(digest)
        val s = m.modPow(key.privateExponent, key.modulus)
        check(s.modPow(key.publicExponent, key.modulus) == m) {
            "L3 signature failed self-verification — this key does not match this BTLD"
        }
        val sig = RsaE3.wordSwap(RsaE3.bigIntToLe(s, modLen))
        val wordCount = modLen / 4
        val prefix = byteArrayOf(
            (wordCount ushr 24).toByte(), (wordCount ushr 16).toByte(),
            (wordCount ushr 8).toByte(), wordCount.toByte(),
        )
        return prefix + sig
    }

    private fun writeBe32(out: ByteArray, at: Int, v: Int) {
        out[at] = (v ushr 24).toByte(); out[at + 1] = (v ushr 16).toByte()
        out[at + 2] = (v ushr 8).toByte(); out[at + 3] = v.toByte()
    }
}
