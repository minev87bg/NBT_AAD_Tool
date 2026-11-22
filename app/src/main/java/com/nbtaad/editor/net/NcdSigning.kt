package com.nbtaad.editor.net

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The coding signature the NBT EVO head unit (CAFD `00001EF6`) re-checks at **every boot**: raw
 * RSA, exponent e=3, no padding, over an MD5 digest of datagroups `0x3000`-`0x300F`, stored in
 * datagroup `0x3010`. Edit a datagroup without recomputing this and the change reverts on reboot —
 * this is the one thing that makes a coding write durable.
 *
 * Ported from vandelayautollc/nbt-evo-toolkit's `sign_ncd.py` / `02-signing-model.md` (MIT
 * licensed) — the "nbtevo" scheme specifically; nothing here is guessed. Ships no key: a [Key]
 * holds only what the *owner's own* recovered `ncd_key.json` (via that toolkit's `nbtevo.keys
 * --kind ncd`, run once against their own PSdZData) provides.
 */
object NcdSigning {
    val CONTENT_DATAGROUPS: List<Int> = (0x3000..0x300F).toList()
    const val SIGNATURE_DATAGROUP: Int = 0x3010

    class Key(modulusHex: String, privateExponentHex: String?, publicExponent: Int = 3) {
        val modulus: BigInteger = BigInteger(modulusHex, 16)
        val privateExponent: BigInteger? = privateExponentHex?.let { BigInteger(it, 16) }
        val publicExponent: BigInteger = BigInteger.valueOf(publicExponent.toLong())
        /** Modulus size in bytes, same rounding the toolkit uses: `(bitLength + 7) / 8`. */
        val nBytes: Int = (modulus.bitLength() + 7) / 8
    }

    /** MD5 over the content datagroups' raw pages, concatenated in ascending order — no separators. */
    fun contentDigest(mem: Map<Int, Int>): ByteArray {
        val pages = Ncd.pages(mem, CONTENT_DATAGROUPS)
        val content = java.io.ByteArrayOutputStream()
        for (dg in CONTENT_DATAGROUPS) pages[dg]?.let { content.write(it) }
        return MessageDigest.getInstance("MD5").digest(content.toByteArray())
    }

    /** Signs a digest with the owner's own recovered private exponent. Needs [Key.privateExponent]. */
    fun signDigest(digest: ByteArray, key: Key): ByteArray {
        val d = key.privateExponent ?: error("this key has no private exponent (public-only key?)")
        val m = RsaE3.leToBigInt(digest)
        val s = m.modPow(d, key.modulus)
        return RsaE3.wordSwap(RsaE3.bigIntToLe(s, key.nBytes))
    }

    /** The signature datagroup's payload: big-endian 32-bit word count, then the signature bytes. */
    fun signaturePage(sig: ByteArray, key: Key): ByteArray {
        val wordCount = key.nBytes / 4
        val prefix = byteArrayOf(
            (wordCount ushr 24).toByte(), (wordCount ushr 16).toByte(),
            (wordCount ushr 8).toByte(), wordCount.toByte(),
        )
        return prefix + sig
    }

    /** Edits [mem]'s signature datagroup to match its current coding content. Call after any byte edit. */
    fun resign(mem: MutableMap<Int, Int>, key: Key): ByteArray {
        val sig = signDigest(contentDigest(mem), key)
        val page = signaturePage(sig, key)
        for (i in page.indices) mem[(SIGNATURE_DATAGROUP shl 8) or i] = page[i].toInt() and 0xFF
        return sig
    }

    /**
     * Reproduces the ECU's own CheckCodingSignature math — needs only the public modulus, so this
     * works even on a key recovered with no private exponent. Fails closed: any shape mismatch
     * (missing page, wrong length, a result too large to be this digest) returns false rather than
     * throwing, because this result gates whether anything gets written to the car.
     */
    fun verify(mem: Map<Int, Int>, key: Key): Boolean {
        val digest = contentDigest(mem)
        val sigPage = Ncd.pages(mem, listOf(SIGNATURE_DATAGROUP))[SIGNATURE_DATAGROUP] ?: return false
        if (sigPage.size < 4 + key.nBytes) return false
        val sig = sigPage.copyOfRange(4, 4 + key.nBytes)
        val d = RsaE3.leToBigInt(RsaE3.wordSwap(sig)).modPow(key.publicExponent, key.modulus)
        if (d.bitLength() > 8 * digest.size) return false
        val recovered = RsaE3.bigIntToLe(d, digest.size)
        return recovered.contentEquals(digest)
    }
}
