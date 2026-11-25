package com.nbtaad.editor.net

/**
 * Builds the "no-signature file-copy container" (WOODOO/IOC-BOLO format) the head unit's SwdlMaster
 * finalizer accepts for a plain file write: a container with NO signature record present is routed to
 * an on-device file-copy job that writes the payload to `<TargetPath>/<Name>` instead of being verified
 * as firmware. Ported from vandelayautollc/nbt-evo-toolkit's `capabilities/flash_and_patching/tools/
 * container.py`'s `build_filecopy_container` (MIT licensed) - proven on-car via the OPT_DEBUG flash
 * (see [FlashWrite]). This file only builds the small container we flash; it does not parse or stream
 * a multi-hundred-MB firmware image - nothing here needs to.
 *
 * Record format (9-byte header + body): `marker:u8, index:u16 LE, length:u32 LE, segment:u8, flags:u8
 * (always 0)`, then `length` bytes of body. Three records make a minimal file-copy container:
 *  - `0x01` stream-start envelope, 1-byte body `0x01`.
 *  - `0x02` descriptor, ASCII metadata `TargetPath=<dir>;Name=<file>;Tag=FIL;CPU=<cpu>;`.
 *  - `0x04` data record, the file's raw bytes.
 */
object FlashContainer {
    private const val MARK_START = 0x01
    private const val MARK_DESC = 0x02
    private const val MARK_DATA = 0x04

    /** `/fs/sda0/opt/...`: live-direct, `O_TRUNC` overwrite in place, effective next boot - but an
     * interrupted transfer leaves the live file truncated (the torn-write risk the runbook warns about;
     * always keep the stock bytes to re-flash as a `-revert` before you touch this path). */
    const val LIVE_PREFIX = "/fs/sda0/opt/"

    /** `/fs/sda0/repository/istep/opt/...`: writes a repository copy only - the live file is untouched
     * until a separate `linkIStep` promotes it, which isn't reachable over the diagnostic bus alone. */
    const val STAGING_PREFIX = "/fs/sda0/repository/istep/opt/"

    private fun record(marker: Int, index: Int, body: ByteArray, segment: Int): ByteArray {
        val out = ByteArray(9 + body.size)
        out[0] = marker.toByte()
        out[1] = index.toByte()
        out[2] = (index ushr 8).toByte()
        out[3] = body.size.toByte()
        out[4] = (body.size ushr 8).toByte()
        out[5] = (body.size ushr 16).toByte()
        out[6] = (body.size ushr 24).toByte()
        out[7] = segment.toByte()
        out[8] = 0x00
        body.copyInto(out, 9)
        return out
    }

    /**
     * Builds the flashable container: [fileBytes] written to `[targetDir][name]` on the head unit.
     * [targetDir] must be one of [LIVE_PREFIX] or [STAGING_PREFIX] (or another absolute, `/`-terminated,
     * traversal-free path) - callers should validate with [validTargetPath] before this is ever sent.
     */
    fun buildFileCopyContainer(
        fileBytes: ByteArray,
        targetDir: String,
        name: String,
        index: Int = 0x0002,
        tag: String = "FIL",
        cpu: String = "Omap",
    ): ByteArray {
        val meta = "TargetPath=$targetDir;Name=$name;Tag=$tag;CPU=$cpu;".toByteArray(Charsets.ISO_8859_1)
        val start = record(MARK_START, 0x0001, byteArrayOf(0x01), segment = 1)
        val desc = record(MARK_DESC, index, meta, segment = 1)
        val data = record(MARK_DATA, index, fileBytes, segment = 1)
        return start + desc + data
    }

    /** The same sanity checks the reference project's offline validator (`check_patch.py`) runs before
     * ever flashing: absolute, no `../` traversal, and ends in `/` so `Name` appends cleanly. */
    fun validTargetPath(path: String): Boolean =
        path.startsWith("/") && path.endsWith("/") && !path.contains("../")
}
