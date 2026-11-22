package com.nbtaad.editor.net

/**
 * The individual (per-ECU/FDL) coding bits that Sq00ky's own project lists as needed for Android
 * Auto to work (github.com/Sq00ky/Supra-iDrive6-Android-Auto, `coding-requirements.md`, including
 * the WiFi Direct items added by issue #294). None of these touch the Vehicle Order/FA — every one
 * lives in a per-ECU coding datagroup on the NBT EVO head unit itself (CAFD `00001EF6`): `0x3000`
 * (HMI), `0x3002` (audio/tuner/traffic) or `0x3003` (phone/telematics/online).
 *
 * The coordinates (datagroup, byte offset, bit mask) are not guesses: they come from
 * vandelayautollc/nbt-evo-toolkit's own reconstructed, firmware-traced coding map for this exact
 * CAFD (MIT licensed; see its `capabilities/coding/definitions/cafd_00001ef6_definitions.md`,
 * which names the same token for each entry below). Reading them is a plain UDS "read data by
 * identifier" in the default session — no SecurityAccess, no key, nothing written to the car.
 */
data class CodingBit(
    /** Matches a heading in Sq00ky's coding-requirements.md, so the two line up at a glance. */
    val group: String,
    val token: String,
    val description: String,
    val datagroup: Int,
    val byteOffset: Int,
    val mask: Int,
    /**
     * The value (after the mask's bits are shifted down) that means "on"/AKTIV. Null for a field
     * whose on-value the map doesn't pin down yet (currently only REMOTE_HMI, a multi-bit access
     * level whose only documented value is "2 = Restricted") — shown as the raw number instead of
     * a set/not-set verdict.
     */
    val expectedOn: Int?,
)

val CODING_BITS: List<CodingBit> = listOf(
    CodingBit(
        "3000 HMI", "REMOTE_HMI", "Remote HMI access level",
        datagroup = 0x3000, byteOffset = 0x6B, mask = 0x70, expectedOn = null,
    ),
    CodingBit(
        "3000 HMI", "A4A", "Apps for Auto (A4A) master enable",
        datagroup = 0x3000, byteOffset = 0x6B, mask = 0x80, expectedOn = 1,
    ),
    CodingBit(
        "3000 HMI", "A4A_REMOTE_APS", "A4A remote apps enabled",
        datagroup = 0x3000, byteOffset = 0x6C, mask = 0x01, expectedOn = 1,
    ),
    CodingBit(
        "3002 AUDIO_TUNER_TRAFFIC", "A4A_ANDROID", "Smartphone apps: Android phones",
        datagroup = 0x3002, byteOffset = 0x51, mask = 0x02, expectedOn = 1,
    ),
    CodingBit(
        "3003 TELEFON_TELEMATIK_ONLINE", "A4A_WIRELESS_ANDROID", "Wireless Android Auto over WiFi",
        datagroup = 0x3003, byteOffset = 0x02, mask = 0x02, expectedOn = 1,
    ),
    CodingBit(
        "WiFi Direct", "ENT_MIRACAST", "Miracast screen mirroring",
        datagroup = 0x3000, byteOffset = 0x06, mask = 0x80, expectedOn = 1,
    ),
    CodingBit(
        "WiFi Direct", "ENT_MC_WLAN_STREAM_DMP", "Media Centre WLAN/DLNA streaming",
        datagroup = 0x3000, byteOffset = 0x0D, mask = 0x80, expectedOn = 1,
    ),
    CodingBit(
        "WiFi Direct", "ENT_MIRACAST_DISPLAY_SETTINGS", "Miracast entry shown in settings menu",
        datagroup = 0x3000, byteOffset = 0x1E, mask = 0x08, expectedOn = 1,
    ),
    CodingBit(
        "WiFi Direct", "WLAN_WIFI_DIRECT", "WLAN Wi-Fi Direct enable",
        datagroup = 0x3003, byteOffset = 0x15, mask = 0x02, expectedOn = 1,
    ),
    CodingBit(
        "WiFi Direct", "WLAN_STATUS", "WLAN status indicator enable",
        datagroup = 0x3003, byteOffset = 0x14, mask = 0x80, expectedOn = 1,
    ),
)

enum class CodingStatus {
    /** Reads as the known "on" value. */
    SET,
    /** Reads, but not as the known "on" value. */
    NOT_SET,
    /** Reads fine, but the map doesn't yet pin down a single "on" value to compare against. */
    UNCERTAIN,
    /** The head unit didn't answer, or doesn't have this datagroup. */
    UNREADABLE,
}

class CodingResult(val bit: CodingBit, val status: CodingStatus, val rawValue: Int?)

object CodingCheck {
    /**
     * Reads every datagroup the known bits live in (today: 0x3000, 0x3002, 0x3003 — one read each,
     * not ten) and evaluates all of [CODING_BITS] against them. Read-only: nothing is written to
     * the car and nothing needs a key. Safe to call as often as the person likes.
     */
    fun checkAll(zgwIp: String): List<CodingResult> {
        val pages = CODING_BITS.map { it.datagroup }.distinct()
            .associateWith { dg -> Hsfz.readCodingDatagroup(zgwIp, dg) }
        return CODING_BITS.map { bit -> evaluate(bit, pages[bit.datagroup]) }
    }

    private fun evaluate(bit: CodingBit, page: ByteArray?): CodingResult {
        if (page == null || bit.byteOffset >= page.size) {
            return CodingResult(bit, CodingStatus.UNREADABLE, null)
        }
        val shift = Integer.numberOfTrailingZeros(bit.mask)
        val raw = ((page[bit.byteOffset].toInt() and 0xFF) and bit.mask) ushr shift
        val status = when {
            bit.expectedOn == null -> CodingStatus.UNCERTAIN
            raw == bit.expectedOn -> CodingStatus.SET
            else -> CodingStatus.NOT_SET
        }
        return CodingResult(bit, status, raw)
    }
}
