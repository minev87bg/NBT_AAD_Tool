package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog
import java.io.IOException

/**
 * Tells NBT EVO apart from the older, original NBT (2012/07-2015/09) by the one fact BMW itself keys
 * everything off: the head unit's own bootloader id (BTLD), read live from its SVK over UDS (`22 F1 01`,
 * diagnostic address 0x63) - not a guess from the filesystem. There is no reliable EVO-vs-original marker
 * visible over the SSH connection the rest of this app uses (HeadUnit's own status read never needed one,
 * since it only ever targeted EVO; JjAa's preflight checks QNX/Cinemo/DSI presence, which both generations
 * plausibly have in some form), so this reads the one id that actually is the hinge.
 *
 * Ported from the reference toolkit's `nbtevo/uds.py` (`decode_svk`/`SgbmId`), which documents the exact
 * byte layout and confirms `00001FFD` = `HU_NBT2` = NBT EVO. Read-only, default session, no SecurityAccess -
 * the same "reads are free" UDS call this app already makes for the VIN and the coding datagroups (see
 * CodingWrite.kt's readVinFrom/readBackup), over the same gateway connection JjAaController.findGateway()
 * already reaches during a normal install - so this should not need any new cable or connection the app
 * doesn't already attempt today, though that is not yet confirmed on a real original-NBT car.
 */
object PlatformCheck {
    /** The bootloader id BMW calls HU_NBT2 - the NBT EVO this app's HeadUnit/InstallController already targets. */
    const val BTLD_EVO = 0x00001FFD

    /** The older, original NBT's own bootloader id - confirmed against robertnisipeanu/mgu-store's mgu7/diag.py,
     * which lists this project's three BTLD ids side by side. Was previously only inferred as "not EVO". */
    const val BTLD_ORIGINAL_NBT = 0x00001275

    /** iDrive 7/iDrive 8's own bootloader id (the MGU hardware), from the same cross-reference. */
    const val BTLD_MGU = 0x00003F36

    private const val DID_SVK = 0xF101
    private const val PROCESS_CLASS_BTLD = 6

    /** One SGBM-coded software/hardware id from an SVK: [processClass][id][main.sub.patch]. */
    class SgbmId(val processClass: Int, val id: Int, val main: Int, val sub: Int, val patch: Int) {
        val idHex: String get() = "%08X".format(id)
    }

    /**
     * Decodes a raw SVK value (the bytes after `62 F1 01`): `[svkVersion u8][progDepChecked u8][count u16 BE]
     * [fingerprint, length varies][N x 8-byte entry]`. Entries are taken from the last `8*count` bytes of the
     * value, since the fingerprint's own length isn't fixed - the same tail-rule the toolkit's own decoder uses.
     */
    fun decodeSvk(value: ByteArray): List<SgbmId> {
        if (value.size < 4) throw IOException("SVK too short: ${value.size} bytes")
        val count = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        val need = count * 8
        if (need > value.size) throw IOException("SVK announces $count entries but has only ${value.size} bytes")
        val first = value.size - need
        return (0 until count).map { i ->
            val at = first + i * 8
            SgbmId(
                processClass = value[at].toInt() and 0xFF,
                id = ((value[at + 1].toInt() and 0xFF) shl 24) or
                    ((value[at + 2].toInt() and 0xFF) shl 16) or
                    ((value[at + 3].toInt() and 0xFF) shl 8) or
                    (value[at + 4].toInt() and 0xFF),
                main = value[at + 5].toInt() and 0xFF,
                sub = value[at + 6].toInt() and 0xFF,
                patch = value[at + 7].toInt() and 0xFF,
            )
        }
    }

    /**
     * Reads the live SVK from the head unit through the car's gateway at [zgwIp] and returns its bootloader
     * entry, or null when there is none (an SVK with no BTLD entry) or the read did not succeed (no gateway
     * answer, a negative response, or a session that could not even connect).
     */
    fun readBtld(zgwIp: String): SgbmId? = try {
        Hsfz.CodingSession(zgwIp).use { session ->
            val o = session.readDataByIdentifier(DID_SVK)
            val data = (o as? Hsfz.CodingSession.Outcome.Positive)?.data
            if (data == null || data.size <= 3) {
                null
            } else {
                decodeSvk(data.copyOfRange(3, data.size))
                    .filter { it.processClass == PROCESS_CLASS_BTLD }
                    .minByOrNull { it.id }
            }
        }
    } catch (e: Exception) {
        AppLog.w("PlatformCheck", "Reading the BTLD from $zgwIp failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
