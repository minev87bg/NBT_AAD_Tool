package com.nbtaad.editor.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * On-device recovery of the owner's own NCD/L3 signing keys from their own PSdZData `security/`
 * files - the same recovery `nbtevo.keys`/`nbtevo.psdz_passphrase` do on a PC, ported so the whole
 * thing runs on the phone instead: no Python, no terminal. Every input here is the owner's own
 * (their `sec_security.lic`, `sec_keyreference.xml`, `sec_ncdkeys.xml`/`sec_auth_l3.xml`, and their
 * PSdZ `resource.jar`); nothing BMW-specific is embedded in this file - it is method only, exactly
 * like the toolkit it's ported from. See docs/keys.md and nbtevo/psdz_passphrase.py upstream.
 */

/**
 * Minimal 8-bit truecolor (RGB), non-interlaced PNG decoder - a direct port of
 * `nbtevo/psdz_passphrase.py`'s own hand-rolled reader. It avoids Android's `BitmapFactory`
 * deliberately, so this stays pure-JVM and parity-testable the same way the rest of this app's
 * crypto/codec ports were (RsaE3, Ncd, L3): a standalone `kotlinc` run against the same PNGs,
 * byte-for-byte, with no Android dependency to stand in the way of that check.
 */
internal object PngRgb8 {
    data class Decoded(val width: Int, val height: Int, val rgb: ByteArray)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun be32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun inflate(zdata: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(zdata)
        val out = ByteArrayOutputStream(maxOf(64, zdata.size * 3))
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Decoded {
        require(
            data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) { "not a PNG" }
        var i = 8
        var w = 0
        var h = 0
        val idat = ByteArrayOutputStream()
        while (i < data.size) {
            val len = be32(data, i)
            val typ = String(data, i + 4, 4, Charsets.US_ASCII)
            val bodyStart = i + 8
            when (typ) {
                "IHDR" -> {
                    w = be32(data, bodyStart)
                    h = be32(data, bodyStart + 4)
                    val bd = data[bodyStart + 8].toInt() and 0xFF
                    val ct = data[bodyStart + 9].toInt() and 0xFF
                    val interlace = data[bodyStart + 12].toInt() and 0xFF
                    require(bd == 8 && ct == 2 && interlace == 0) {
                        "expected 8-bit non-interlaced RGB PNG (got bitdepth=$bd colortype=$ct interlace=$interlace)"
                    }
                }
                "IDAT" -> idat.write(data, bodyStart, len)
                "IEND" -> { i = data.size }
            }
            if (typ != "IEND") i = bodyStart + len + 4
        }
        val raw = inflate(idat.toByteArray())
        val stride = w * 3
        val out = ByteArray(stride * h)
        var prev = ByteArray(stride)
        var p = 0
        for (row in 0 until h) {
            val f = raw[p].toInt() and 0xFF
            p += 1
            val line = raw.copyOfRange(p, p + stride)
            p += stride
            when (f) {
                0 -> {}
                1 -> for (x in 3 until stride) line[x] = (line[x] + line[x - 3]).toByte()
                2 -> for (x in 0 until stride) line[x] = (line[x] + prev[x]).toByte()
                3 -> for (x in 0 until stride) {
                    val a = if (x >= 3) line[x - 3].toInt() and 0xFF else 0
                    line[x] = (line[x] + ((a + (prev[x].toInt() and 0xFF)) shr 1)).toByte()
                }
                4 -> for (x in 0 until stride) {
                    val a = if (x >= 3) line[x - 3].toInt() and 0xFF else 0
                    val c = if (x >= 3) prev[x - 3].toInt() and 0xFF else 0
                    line[x] = (line[x] + paeth(a, prev[x].toInt() and 0xFF, c)).toByte()
                }
                else -> error("unknown PNG filter $f")
            }
            System.arraycopy(line, 0, out, row * stride, stride)
            prev = line
        }
        return Decoded(w, h, out)
    }
}

/** The steganographic payload hidden in a BMW logo PNG - a port of `ResourceParser.processImg`. */
internal object Stego {
    fun payload(png: ByteArray): ByteArray {
        val (w, h, px) = PngRgb8.decode(png)
        val twoBits = ArrayList<Int>((h - 1).coerceAtLeast(0) * (w - 1).coerceAtLeast(0) * 3)
        for (row in 0 until h) {
            if (row == 0) continue
            val base = row * w * 3
            for (col in 0 until w) {
                if (col == 0) continue
                val o = base + col * 3
                twoBits.add(px[o].toInt() and 3)
                twoBits.add(px[o + 1].toInt() and 3)
                twoBits.add(px[o + 2].toInt() and 3)
            }
        }
        val chars = StringBuilder()
        var i = 0
        while (i + 2 < twoBits.size) {
            val byte = twoBits[i] or (twoBits[i + 1] shl 2) or (twoBits[i + 2] shl 4)
            val ch = (byte + 32).toChar()
            chars.append(ch)
            i += 3
            if (ch == '_') break
        }
        if (chars.isNotEmpty() && chars.last() == '_') chars.deleteCharAt(chars.length - 1)
        return hexDecodeStrict(chars.toString())
    }

    private fun hexDecodeStrict(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex string decoded from a stego PNG" }
        val out = ByteArray(s.length / 2)
        for (idx in out.indices) {
            val hi = Character.digit(s[idx * 2], 16)
            val lo = Character.digit(s[idx * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex character decoded from a stego PNG" }
            out[idx] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** One recovered BTLD-scoped signing key, ready to feed into [CodingKeyStore]. */
data class RecoveredKey(
    val btld: String,
    val kind: String,
    val scheme: String,
    val digest: String,
    val testKey: Boolean,
    val publicExponent: Int,
    val modulusHex: String,
    val privateExponentHex: String,
) {
    /** Same field names as the PC tool's `ncd_key.json`/`l3_key.json` - [CodingKeyStore] stores this verbatim. */
    fun toJson(): String = org.json.JSONObject().apply {
        put("btld", btld)
        put("kind", kind)
        put("scheme", scheme)
        put("digest", digest)
        put("test_key", testKey)
        put("public_exponent", publicExponent)
        put("modulus_hex", modulusHex)
        put("private_exponent_hex", privateExponentHex)
    }.toString()
}

/**
 * The on-device port of `nbtevo.psdz_passphrase` + `nbtevo.keys.recover()`: everything a PC run of
 * `python -m nbtevo.keys` does, using only `javax.crypto`/`java.security`/`javax.xml` - no Android
 * KeyStore, no network, nothing beyond what the caller hands it. Every call here is local, pure
 * computation over bytes the caller supplies; nothing is cached or written except by the caller.
 */
object PsdzKeyRecovery {

    // ---- shared helpers -------------------------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun localName(n: Node): String = n.localName ?: n.nodeName.substringAfterLast(':')

    private fun allElements(root: Node): List<Element> {
        val list = ArrayList<Element>()
        fun walk(n: Node) {
            if (n.nodeType == Node.ELEMENT_NODE) list.add(n as Element)
            var c = n.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(root)
        return list
    }

    /** Hex if it looks like hex (even length, all hex digits), else base64 - matches `_blob()`. */
    private fun blob(s: String?): ByteArray {
        val stripped = (s ?: "").replace(Regex("\\s+"), "")
        val looksHex = stripped.isNotEmpty() && stripped.length % 2 == 0 &&
            stripped.all { (it in '0'..'9') || (it.lowercaseChar() in 'a'..'f') }
        if (looksHex) {
            val out = ByteArray(stripped.length / 2)
            for (idx in out.indices) {
                out[idx] = ((Character.digit(stripped[idx * 2], 16) shl 4) or
                    Character.digit(stripped[idx * 2 + 1], 16)).toByte()
            }
            return out
        }
        return java.util.Base64.getDecoder().decode(stripped)
    }

    // ---- 1. keystore password: derived from the owner's own logo PNGs, never typed -----------

    /**
     * `sec_security.lic` is an XML wrapper: `<... pid="N">BASE64(PKCS#12)</...>`.
     * Returns (pid, pkcs12Der). If the file doesn't parse as XML, it's already an unwrapped
     * PKCS#12 and pid is null - no password can be derived from it in that case.
     */
    fun parseLic(licBytes: ByteArray): Pair<String?, ByteArray> {
        return try {
            val doc = parseXml(licBytes)
            val root = doc.documentElement
            val pid = root.getAttribute("pid").ifEmpty { null }
            val inner = (root.textContent ?: "").trim()
            pid to java.util.Base64.getDecoder().decode(inner)
        } catch (e: Exception) {
            null to licBytes
        }
    }

    /** Decodes the `<pplist>` hidden in the two logo PNGs into {pid: passphrase}. */
    fun recoverPassphrases(logoPng: ByteArray, miniLogoPng: ByteArray): Map<String, String> {
        val ciphertext = Stego.payload(logoPng)
        val key = Stego.payload(miniLogoPng)
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "unexpected AES key length ${key.size} decoded from the mini logo"
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ByteArray(16)),
        )
        val padded = cipher.doFinal(ciphertext)
        val padLen = padded.last().toInt() and 0xFF
        require(padLen in 1..16 && padLen <= padded.size) { "bad PKCS7 padding on the decoded pplist" }
        for (k in padded.size - padLen until padded.size) {
            require((padded[k].toInt() and 0xFF) == padLen) { "bad PKCS7 padding on the decoded pplist" }
        }
        val xml = padded.copyOfRange(0, padded.size - padLen)
        val doc = parseXml(xml)
        val out = HashMap<String, String>()
        for (el in allElements(doc)) {
            if (localName(el) == "passphrase") {
                val id = el.getAttribute("id")
                if (id.isNotEmpty()) out[id] = el.textContent ?: ""
            }
        }
        require(out.isNotEmpty()) { "no <passphrase> entries in the decoded pplist" }
        return out
    }

    /**
     * `HEX_UPPER(SHA1(passphrase[pid]))` - the PKCS#12 password for `sec_security.lic`, derived
     * (never typed) exactly as PSdZ derives it. `pid` comes from the `.lic`; the passphrase comes
     * from the owner's own two logo PNGs.
     */
    fun deriveKeystorePassword(licBytes: ByteArray, logoPng: ByteArray, miniLogoPng: ByteArray): ByteArray {
        val (pid, _) = parseLic(licBytes)
        requireNotNull(pid) { "no pid attribute on sec_security.lic - is it the XML-wrapped file?" }
        val pps = recoverPassphrases(logoPng, miniLogoPng)
        val pass = pps[pid] ?: throw NoSuchElementException(
            "sec_security.lic pid=$pid not among the decoded passphrases ${pps.keys.sorted()}"
        )
        val sha1 = MessageDigest.getInstance("SHA-1").digest(pass.toByteArray(Charsets.US_ASCII))
        return sha1.joinToString("") { "%02X".format(it) }.toByteArray(Charsets.US_ASCII)
    }

    // ---- 2. the E-SYS private key, then the BTLD-scoped NCD/L3 key ---------------------------

    private fun loadEsysPrivateKey(pkcs12Der: ByteArray, password: ByteArray): PrivateKey {
        val ks = KeyStore.getInstance("PKCS12")
        val pwChars = String(password, Charsets.US_ASCII).toCharArray()
        ks.load(ByteArrayInputStream(pkcs12Der), pwChars)
        val alias = ks.aliases().asSequence().firstOrNull { ks.isKeyEntry(it) }
            ?: throw NoSuchElementException("no private key in sec_security.lic (wrong derived password?)")
        return ks.getKey(alias, pwChars) as PrivateKey
    }

    /** RSA-unwraps the AES session key named by `refName` (or any that decrypts) from sec_keyreference.xml. */
    private fun unwrapSessionKey(keyrefXml: ByteArray, esysPriv: PrivateKey, refName: String?): ByteArray {
        val doc = parseXml(keyrefXml)
        val entries = ArrayList<Pair<String?, String>>()
        for (sk in allElements(doc).filter { localName(it) == "SessionKey" }) {
            val descendants = allElements(sk)
            val ref = descendants.firstOrNull { localName(it).lowercase() == "reference" }
                ?.textContent?.trim()
            val kd = descendants.firstOrNull {
                localName(it).lowercase() == "keydata" && (it.textContent ?: "").trim().isNotEmpty()
            }?.textContent
            if (kd != null) entries.add(ref to kd)
        }

        fun unwrap(text: String): ByteArray {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, esysPriv)
            val key = cipher.doFinal(blob(text))
            return if (key.size == 16 || key.size == 24 || key.size == 32) key
            else key.copyOfRange(key.size - 16, key.size)
        }

        if (refName != null) {
            val exact = entries.filter { it.first == refName }.map { it.second }
            val substr = entries.filter { it.first != null && it.first!!.contains(refName) }.map { it.second }
            val candidates = if (exact.isNotEmpty()) exact else substr
            if (candidates.isNotEmpty()) return unwrap(candidates[0])
        }
        for ((_, text) in entries) {
            try { return unwrap(text) } catch (e: Exception) { continue }
        }
        throw NoSuchElementException("could not unwrap any session key (ref ${refName ?: "<none>"})")
    }

    /** All text + attribute values under [el], lowercased - used to locate the BTLD entry. */
    private fun searchableText(el: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (n.nodeType == Node.ELEMENT_NODE) {
                val attrs = (n as Element).attributes
                for (i in 0 until attrs.length) sb.append(attrs.item(i).nodeValue).append(' ')
            } else if (n.nodeType == Node.TEXT_NODE) {
                sb.append(n.nodeValue)
            }
            var c = n.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(el)
        return sb.toString().lowercase()
    }

    /**
     * Finds the `elemLocalName` entry for [btld] in a sec_ncdkeys.xml/sec_auth_l3.xml, by
     * searching text/attribute content for "btld-<id>" or the id's own digits at a word boundary
     * (any leading zeros). Deviates from the Python reference's "serialize the subtree to a
     * string" check by searching collected text+attributes directly instead - equivalent for any
     * real BMW file (a BTLD id only ever appears in an attribute value or text, never in element
     * syntax), and fails safely (not found) rather than matching wrong if that assumption is ever
     * off, since this only gates which entry we try to decrypt, not whether a recovered key is
     * trusted (the signature self-test is what actually gates that).
     */
    private fun findBtldEntry(xmlBytes: ByteArray, btld: String, elemLocalName: String): Pair<ByteArray, Map<String, String>> {
        val doc = parseXml(xmlBytes)
        val btldLower = btld.lowercase()
        val btldNorm = btldLower.trimStart('0').ifEmpty { "0" }
        val wordRegex = Regex("\\b0*${Regex.escape(btldNorm)}\\b")
        for (el in allElements(doc)) {
            if (localName(el) != elemLocalName) continue
            val text = searchableText(el)
            if (!(text.contains("btld-$btldLower") || wordRegex.containsMatchIn(text))) continue
            val kd = allElements(el).firstOrNull {
                val ln = localName(it).lowercase()
                (ln == "keydata" || ln == "key" || ln == "privatekey") &&
                    (it.textContent ?: "").trim().isNotEmpty()
            }
            if (kd != null) {
                val attrs = HashMap<String, String>()
                val a = kd.attributes
                for (i in 0 until a.length) attrs[a.item(i).nodeName] = a.item(i).nodeValue
                return blob(kd.textContent) to attrs
            }
        }
        throw NoSuchElementException("no key entry for BTLD $btld in this file")
    }

    private fun pkcs5Unpad(pt: ByteArray): ByteArray {
        if (pt.isEmpty()) return pt
        val padLen = pt.last().toInt() and 0xFF
        return if (padLen in 1..16) pt.copyOfRange(0, pt.size - padLen) else pt
    }

    private val DIGEST_MAP = mapOf(
        "MD5" to "md5", "SHA-256" to "sha256", "SHA256" to "sha256", "SHA-1" to "sha1",
    )

    /**
     * The full chain, end to end, for one key ("ncd" or "l3"): derive the keystore password from
     * the owner's own logo PNGs, open their E-SYS PKCS#12, RSA-unwrap the AES session key, AES
     * decrypt the BTLD entry, and parse the PKCS#8 RSA private key inside it.
     *
     * @param securityLic   the owner's `security/sec_security.lic`
     * @param keyrefXml     the owner's `security/sec_keyreference.xml`
     * @param srcXml        the owner's `security/sec_ncdkeys.xml` (kind="ncd") or `sec_auth_l3.xml` (kind="l3")
     * @param logoPng       the owner's `bmw_logo.png` (from their PSdZ `resource.jar`)
     * @param miniLogoPng   the owner's `bmw_mini_logo.png` (from the same `resource.jar`)
     */
    fun recover(
        securityLic: ByteArray,
        keyrefXml: ByteArray,
        srcXml: ByteArray,
        btld: String,
        kind: String,
        logoPng: ByteArray,
        miniLogoPng: ByteArray,
    ): RecoveredKey {
        require(kind == "ncd" || kind == "l3") { "kind must be \"ncd\" or \"l3\"" }
        val elemLocalName = if (kind == "ncd") "NCodSigKey" else "AuthenticationKey"
        val refHint = if (kind == "ncd") "NCDS" else "AS_L3"

        val password = deriveKeystorePassword(securityLic, logoPng, miniLogoPng)
        val (_, pkcs12Der) = parseLic(securityLic)
        val esysPriv = loadEsysPrivateKey(pkcs12Der, password)

        val srcDoc = parseXml(srcXml)
        val refName = srcDoc.documentElement.getAttribute("encKeyRef").ifEmpty { refHint }
        val aesKey = unwrapSessionKey(keyrefXml, esysPriv, refName)

        val (encrypted, attrs) = findBtldEntry(srcXml, btld, elemLocalName)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
            javax.crypto.spec.IvParameterSpec(ByteArray(16)),
        )
        val decrypted = pkcs5Unpad(cipher.doFinal(encrypted))

        val keyFactory = KeyFactory.getInstance("RSA")
        val priv = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decrypted)) as RSAPrivateCrtKey

        val digestAttr = attrs.entries.firstOrNull { it.key.substringAfterLast(':').lowercase() == "digestalg" }?.value
        val schemeAttr = attrs.entries.firstOrNull { it.key.substringAfterLast(':').lowercase() == "signaturescheme" }?.value
        val testKeyAttr = attrs.entries.firstOrNull { it.key.substringAfterLast(':').lowercase() == "testkey" }?.value

        return RecoveredKey(
            btld = btld.uppercase(),
            kind = kind,
            scheme = schemeAttr ?: "BMWSigVerify",
            digest = DIGEST_MAP[(digestAttr ?: "").uppercase().replace(" ", "-")] ?: "md5",
            testKey = (testKeyAttr ?: "false").equals("true", ignoreCase = true),
            publicExponent = priv.publicExponent.toInt(),
            modulusHex = priv.modulus.toString(16),
            privateExponentHex = priv.privateExponent.toString(16),
        )
    }
}
