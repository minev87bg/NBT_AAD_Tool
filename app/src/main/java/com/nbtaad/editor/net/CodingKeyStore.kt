package com.nbtaad.editor.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the owner's own recovered coding keys — `ncd_key.json` and `l3_key.json`, produced once on
 * a PC by vandelayautollc/nbt-evo-toolkit's `nbtevo.keys` against the owner's own PSdZData — in the
 * app's private storage, encrypted with a key that never leaves the Android Keystore. In that
 * toolkit's own words these two small files are "the authority to change your car"; they're treated
 * that way here too: never logged, never sent anywhere, decrypted only in memory for as long as a
 * write actually needs them.
 *
 * `androidx.security:security-crypto` was deliberately not used for this — Google has deprecated it
 * and ships no further versions — so this talks to the Android Keystore directly instead.
 */
class CodingKeyStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "coding_keys").apply { mkdirs() }
    private val ncdFile = File(dir, "ncd_key.enc")
    private val l3File = File(dir, "l3_key.enc")

    private val androidKeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val alias = "nbt_aad_coding_key"
        (androidKeyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val body = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun write(file: File, json: String) = file.writeBytes(encrypt(json.toByteArray(Charsets.UTF_8)))

    private fun read(file: File): String? =
        file.takeIf { it.exists() }?.let { String(decrypt(it.readBytes()), Charsets.UTF_8) }

    fun hasNcdKey(): Boolean = ncdFile.exists()
    fun hasL3Key(): Boolean = l3File.exists()
    fun hasBothKeys(): Boolean = hasNcdKey() && hasL3Key()

    /** Stores the owner's own `ncd_key.json` verbatim (encrypted at rest), after a sanity check that it parses. */
    fun importNcdKey(json: String) {
        val o = JSONObject(json)
        require(o.has("modulus_hex")) { "Doesn't look like an ncd_key.json (no modulus_hex)." }
        write(ncdFile, json)
    }

    /** Stores the owner's own `l3_key.json` verbatim (encrypted at rest), after a sanity check that it parses. */
    fun importL3Key(json: String) {
        val o = JSONObject(json)
        require(o.has("modulus_hex") && o.has("private_exponent_hex")) {
            "Doesn't look like an l3_key.json (needs modulus_hex and private_exponent_hex)."
        }
        write(l3File, json)
    }

    fun readNcdKey(): NcdSigning.Key? = read(ncdFile)?.let { json ->
        val o = JSONObject(json)
        val priv = if (o.has("private_exponent_hex")) o.getString("private_exponent_hex") else null
        NcdSigning.Key(o.getString("modulus_hex"), priv, o.optInt("public_exponent", 3))
    }

    fun readL3Key(): L3.Key? = read(l3File)?.let { json ->
        val o = JSONObject(json)
        L3.Key(o.getString("modulus_hex"), o.getString("private_exponent_hex"), o.optInt("public_exponent", 3))
    }

    /** "Forget these keys": deletes both files. The PC-side recovery can always produce them again. */
    fun clearKeys() {
        ncdFile.delete()
        l3File.delete()
    }
}
