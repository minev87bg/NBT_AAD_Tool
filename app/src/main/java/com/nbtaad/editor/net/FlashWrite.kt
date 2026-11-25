package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog

/** What a flash attempt did, step by step - the flash-only counterpart to [WriteOutcome]: no NCD text
 * (there's no coding re-read after a flash), same "never paper over a failed step" reporting contract. */
class FlashOutcome(val steps: List<WriteStep>, val success: Boolean)

/**
 * Runs a UDS **flash** write to the NBT EVO head unit - the mechanism that unlocks/locks the SSH/telnet
 * dev surface (see `FlashWrite.flash`'s own doc for the OPT_DEBUG specifics), and the general engine any
 * future file-copy flash on this unit would reuse. Ported frame-for-frame from vandelayautollc/
 * nbt-evo-toolkit's `capabilities/flash_and_patching/tools/flash_plan.py` and
 * `03-flashing-safely.md` (MIT licensed) - the toolkit's own words: "proven on-car: the OPT_DEBUG
 * file-copy flash was run over the UDS route (in both a Pi tool and the Android app), authenticated
 * with the L3 handshake, committed, and brought up the dev/SSH surface after reboot".
 *
 * Mirrors [CodingWrite]'s shape (step-by-step [WriteStep]s, abort-on-first-failure, the car's own VIN
 * checked before anything is touched) with one addition specific to flashing: the teardown
 * (leave programming mode, restore comms/DTC logging, conditionally reset) always runs in full, in
 * order, **even when the write above aborted partway** - an interrupted flash still leaves the car in
 * programming mode otherwise, which the runbook is explicit must not be left that way.
 */
object FlashWrite {
    /** The bootloader (BTLD) id this whole key/flash story is bound to - same default as [L3.signSeed].
     * [checkBootloader] reads the car's own SVK and refuses to assume this rather than trust it blindly. */
    private const val BTLD_EXPECTED = 0x00001FFD

    private fun describeOutcome(label: String, o: Hsfz.FlashSession.Outcome): WriteStep = when (o) {
        is Hsfz.FlashSession.Outcome.Positive -> WriteStep(label, true, "OK")
        is Hsfz.FlashSession.Outcome.Negative -> WriteStep(label, false, "rejected (NRC 0x%02X)".format(o.nrc))
        Hsfz.FlashSession.Outcome.NoResponse -> WriteStep(label, false, "no response")
    }

    /**
     * READ-ONLY: reads the SVK (`22 F1 01`) and confirms the car's bootloader id matches [BTLD_EXPECTED] -
     * the id the L3 key and this whole flash method are bound to. Per the reference project's own stated
     * rule ("read the SVK first... another bootloader refuses SecurityAccess outright, and the whole key
     * story assumes it"), this is checked rather than assumed. Nothing is written; call before ever
     * offering the flash UI.
     */
    fun checkBootloader(zgwIp: String): Pair<Boolean, String> {
        return try {
            Hsfz.FlashSession(zgwIp).use { s ->
                val o = s.readDataByIdentifier(0xF101, timeoutMs = 5000)
                when (o) {
                    is Hsfz.FlashSession.Outcome.Positive -> {
                        val svk = if (o.data.size > 3) Svk.decode(o.data.copyOfRange(3, o.data.size)) else null
                        val btld = svk?.bootloader
                        when {
                            svk == null -> false to "Could not read the SVK."
                            btld == null -> false to "The SVK has no bootloader (BTLD) entry."
                            btld.id.toInt() != BTLD_EXPECTED -> false to
                                "Bootloader is %08X, not %08X - this flash method is not confirmed for this unit."
                                    .format(btld.id, BTLD_EXPECTED)
                            else -> true to "Bootloader %08X confirmed.".format(btld.id)
                        }
                    }
                    is Hsfz.FlashSession.Outcome.Negative -> false to "SVK read rejected (NRC 0x%02X).".format(o.nrc)
                    Hsfz.FlashSession.Outcome.NoResponse -> false to "SVK read: no response."
                }
            }
        } catch (e: Exception) {
            AppLog.e("Flash", "Reading the SVK failed", e)
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** The SGBM id psdzdata-manifest.md tags "NBT Core / Media / Connectivity / Nav - the OPT_DEBUG
     * flash target": the build identity a captured `starter.cfg` is labeled with, so a stock file read
     * off one car can later be recognized as matching another car's exact same software. */
    private const val CORE_SWFL_ID = 0x0000271AL

    /**
     * READ-ONLY: reads the SVK and returns the core SWFL (`0000271A`)'s version - purely a label for
     * whatever `starter.cfg` gets read alongside it (see [StarterCfgRemote]/[StarterCfgStore]). Null if
     * the SVK can't be read or has no such entry; nothing here writes anything or gates a flash.
     */
    fun readCoreSwflVersion(zgwIp: String): String? {
        return try {
            Hsfz.FlashSession(zgwIp).use { s ->
                val o = s.readDataByIdentifier(0xF101, timeoutMs = 5000)
                val data = (o as? Hsfz.FlashSession.Outcome.Positive)?.data ?: return null
                val svk = if (data.size > 3) Svk.decode(data.copyOfRange(3, data.size)) else null
                svk?.entry("SWFL", CORE_SWFL_ID)?.version
            }
        } catch (e: Exception) {
            AppLog.e("Flash", "Reading the core SWFL version failed", e)
            null
        }
    }

    /**
     * Writes [containerBytes] (a no-signature file-copy container - see [FlashContainer]) to the head
     * unit. Confirms the connected VIN is [ownVin] before touching anything, authenticates with
     * [l3Key] via the flash-level (`27 11`/`27 12`) SecurityAccess - the **same** key and seed→key
     * construction the coding write already uses ([L3.signSeed]; per docs/keys.md, "every write session
     * unlocks with the same seed→key construction, whether it's coding... or flash") - then runs the
     * erase/download/transfer/exit/check sequence, and always runs the full teardown afterward
     * regardless of where the write stopped.
     */
    fun flash(zgwIp: String, containerBytes: ByteArray, l3Key: L3.Key, ownVin: String): FlashOutcome {
        val steps = mutableListOf<WriteStep>()
        try {
            Hsfz.FlashSession(zgwIp).use { s ->
                val vinOutcome = s.readDataByIdentifier(0xF190)
                val vin = (vinOutcome as? Hsfz.FlashSession.Outcome.Positive)?.data
                    ?.let { if (it.size > 3) String(it, 3, it.size - 3, Charsets.ISO_8859_1).trim('\u0000', ' ') else null }
                if (vin == null || !vin.equals(ownVin.trim(), ignoreCase = true)) {
                    steps.add(WriteStep("Confirm VIN", false, "vehicle is ${vin ?: "unreadable"}, expected ${ownVin.trim()} — refusing to flash"))
                    return FlashOutcome(steps, false)
                }
                steps.add(WriteStep("Confirm VIN", true, vin))

                // === 1. Enter programming mode ===
                steps.add(describeOutcome("Enter extended session", s.enterSession(0x03)))
                steps.add(describeOutcome("Read flash timing", s.readDataByIdentifier(0x2504)))

                val activate = s.routineControl(0x01, 0x0F0C, byteArrayOf(0x03))
                steps.add(describeOutcome("ActivateFlash (energy on)", activate))
                if (activate !is Hsfz.FlashSession.Outcome.Positive) return teardown(s, steps, false)

                val energyOutcome = s.readDataByIdentifier(0x100A)
                val energyVal = (energyOutcome as? Hsfz.FlashSession.Outcome.Positive)?.data
                    ?.let { if (it.size > 3) it[3].toInt() and 0xFF else null }
                steps.add(
                    WriteStep(
                        "Read EnergyMode (must be non-zero)",
                        energyVal != null && energyVal != 0,
                        energyVal?.let { "0x%02X".format(it) } ?: describeOutcome("", energyOutcome).detail,
                    ),
                )
                if (energyVal == null || energyVal == 0) return teardown(s, steps, false)

                steps.add(describeOutcome("DTC off", s.dtcControl(0x02)))
                steps.add(describeOutcome("CommControl quiet", s.commControl(0x01)))

                val extMode = s.routineControl(0x01, 0x1003, byteArrayOf(0x01))
                steps.add(describeOutcome("SetExtMode on", extMode))
                if (extMode !is Hsfz.FlashSession.Outcome.Positive) return teardown(s, steps, false)

                steps.add(describeOutcome("Re-enter extended session", s.enterSession(0x03)))
                steps.add(describeOutcome("Re-read flash timing", s.readDataByIdentifier(0x2504)))

                val progSession = s.enterSession(0x02, timeoutMs = 3000)
                if (progSession is Hsfz.FlashSession.Outcome.Negative && progSession.nrc == 0x22) {
                    // "prep insufficient" - the one case the runbook calls an explicit abort, before the
                    // HU has actually rebooted, so there is nothing yet to reconnect to.
                    steps.add(WriteStep("Enter programming session", false, "rejected: prep insufficient (NRC 0x22)"))
                    return teardown(s, steps, false)
                }
                steps.add(
                    WriteStep(
                        "Enter programming session (head unit reboots into bootloader)",
                        true,
                        "sent" + if (progSession is Hsfz.FlashSession.Outcome.Positive) " (acked)" else " (no reply expected - rebooting)",
                    ),
                )
                s.reconnect()
                val inBootloader = s.pollSession(wantSession = 0x02, maxWaitMs = 50_000)
                steps.add(
                    WriteStep(
                        "Wait for bootloader (poll 22 F186)", inBootloader,
                        if (inBootloader) "in bootloader" else "timed out after 50s",
                    ),
                )
                if (!inBootloader) return teardown(s, steps, false)

                // === 2. Authenticate (flash-level SecurityAccess, the same L3 key as coding) ===
                val seedOutcome = s.securityAccessSeedFlash()
                steps.add(describeOutcome("SecurityAccess: request seed (flash level)", seedOutcome))
                val seed = (seedOutcome as? Hsfz.FlashSession.Outcome.Positive)?.data
                    ?.let { if (it.size >= 10) it.copyOfRange(2, 10) else null }
                if (seed == null) return teardown(s, steps, false)

                val key = try {
                    L3.signSeed(seed, l3Key)
                } catch (e: Exception) {
                    steps.add(WriteStep("Compute L3 key response", false, e.message ?: "failed"))
                    return teardown(s, steps, false)
                }
                val saOutcome = s.securityAccessSendKeyFlash(key)
                steps.add(describeOutcome("SecurityAccess: send key (flash level)", saOutcome))
                if (saOutcome !is Hsfz.FlashSession.Outcome.Positive) return teardown(s, steps, false)

                // === 3. Write ===
                val fp = s.writeDataByIdentifier(0xF15A, ByteArray(13))
                steps.add(WriteStep("Write fingerprint (NRC ignored)", true, describeOutcome("", fp).detail))

                val erase = s.eraseMemory(lbn = 0)
                steps.add(describeOutcome("Erase (indicated, LBN 0)", erase))
                if (erase !is Hsfz.FlashSession.Outcome.Positive) return teardown(s, steps, false)

                val dl = s.requestDownload(containerBytes.size)
                steps.add(describeOutcome("Request download (${containerBytes.size} bytes)", dl))
                val maxBlock = (dl as? Hsfz.FlashSession.Outcome.Positive)?.data?.let { parseMaxBlockLength(it) }
                if (maxBlock == null || maxBlock <= 2) {
                    steps.add(WriteStep("Negotiate block size", false, "no usable maxNumberOfBlockLength in the reply"))
                    return teardown(s, steps, false)
                }

                val chunkSize = maxBlock - 2
                var offset = 0
                var bsc = 1
                var allBlocksOk = true
                while (offset < containerBytes.size) {
                    val end = minOf(offset + chunkSize, containerBytes.size)
                    val block = containerBytes.copyOfRange(offset, end)
                    var sent = false
                    var tries = 0
                    while (!sent && tries < 6) {
                        tries++
                        s.testerPresent()
                        when (val r = s.transferData(bsc, block)) {
                            is Hsfz.FlashSession.Outcome.Positive -> sent = true
                            is Hsfz.FlashSession.Outcome.Negative -> when (r.nrc) {
                                0x73 -> sent = true // wrongBlockSequenceCounter: ECU already advanced - treat as success
                                0x21, 0x7F -> Thread.sleep(300) // busy/pending - brief wait and resend
                                else -> {
                                    steps.add(WriteStep("Transfer block @0x%X".format(offset), false, "rejected (NRC 0x%02X)".format(r.nrc)))
                                    tries = 6
                                }
                            }
                            Hsfz.FlashSession.Outcome.NoResponse -> Unit // retry
                        }
                    }
                    if (!sent) { allBlocksOk = false; break }
                    offset = end
                    bsc = if (bsc == 0xFF) 0x00 else bsc + 1
                }
                steps.add(
                    WriteStep(
                        "Transfer data (${containerBytes.size} bytes)", allBlocksOk,
                        if (allBlocksOk) "all blocks sent" else "stopped early at offset $offset",
                    ),
                )
                if (!allBlocksOk) return teardown(s, steps, false)

                val exit = s.requestTransferExit(timeoutMs = 20_000)
                steps.add(describeOutcome("Request transfer exit (file is written here)", exit))
                val wroteOk = exit is Hsfz.FlashSession.Outcome.Positive

                // === 4. Verify (both non-fatal per the runbook - log and continue either way) ===
                val check = s.checkMemory(lbn = 0)
                steps.add(WriteStep("Check memory (non-fatal)", check is Hsfz.FlashSession.Outcome.Positive, describeOutcome("", check).detail))
                val deps = s.checkProgrammingDependencies()
                steps.add(WriteStep("Check programming dependencies (non-fatal)", deps is Hsfz.FlashSession.Outcome.Positive, describeOutcome("", deps).detail))

                return teardown(s, steps, wroteOk)
            }
        } catch (e: Exception) {
            AppLog.e("Flash", "Flash failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
            return FlashOutcome(steps, false)
        }
    }

    /** `74 <lengthFormatId><maxNumberOfBlockLength>` - the high nibble of the format id is how many
     * bytes the block-length field itself occupies (big-endian). Returns null if the reply is too
     * short to hold what it claims. */
    private fun parseMaxBlockLength(resp: ByteArray): Int? {
        if (resp.size < 2) return null
        val nBytes = (resp[1].toInt() and 0xFF) ushr 4
        if (nBytes <= 0 || resp.size < 2 + nBytes) return null
        var v = 0
        for (i in 0 until nBytes) v = (v shl 8) or (resp[2 + i].toInt() and 0xFF)
        return v
    }

    /**
     * The mandatory teardown - run in full, in order, **whether the write above succeeded or aborted
     * partway** (the runbook: "do all of it, in order — even if you aborted"): leave programming mode
     * (`ActivateApplicationMode` first, since that alone settles the energy window), wait for the app
     * session to come back, drop extended/energy-on mode, restore normal comms and DTC logging, and -
     * only if EnergyMode and the extended-session exit both read clean - reset the ECU. An unconditional
     * reset here could strand the head unit in a programming/energy-on state, so a dirty read skips it
     * and says so, rather than resetting anyway and hoping.
     */
    private fun teardown(s: Hsfz.FlashSession, steps: MutableList<WriteStep>, writeSucceeded: Boolean): FlashOutcome {
        val activateApp = s.routineControl(0x01, 0x100F)
        steps.add(describeOutcome("ActivateApplicationMode", activateApp))
        s.reconnect()
        val backInApp = s.pollSession(wantSession = 0x01, maxWaitMs = 40_000)
        steps.add(WriteStep("Wait for app session (poll 22 F186)", backInApp, if (backInApp) "back in app" else "timed out after 40s"))

        steps.add(describeOutcome("Re-enter extended session", s.enterSession(0x03)))
        steps.add(describeOutcome("EnergyOff", s.routineControl(0x01, 0x0F0C, byteArrayOf(0x00))))
        val (energyClean, lastEnergy) = s.pollEnergyMode(wantZero = true, maxWaitMs = 50_000)
        steps.add(WriteStep("Wait for EnergyMode == 0", energyClean, lastEnergy?.let { "0x%02X".format(it) } ?: "unreadable"))

        val extOff = s.routineControl(0x01, 0x1003, byteArrayOf(0x00))
        val extClean = extOff is Hsfz.FlashSession.Outcome.Positive
        steps.add(describeOutcome("SetExtMode off", extOff))
        steps.add(describeOutcome("CommControl normal", s.commControl(0x00)))
        steps.add(describeOutcome("DTC on", s.dtcControl(0x01)))

        if (energyClean && extClean) {
            steps.add(describeOutcome("Reset head unit", s.ecuReset()))
        } else {
            steps.add(WriteStep("Reset head unit", false, "skipped - EnergyMode/ExtendedMode did not read clean; power-cycle the head unit instead"))
        }
        return FlashOutcome(steps, writeSucceeded)
    }
}
