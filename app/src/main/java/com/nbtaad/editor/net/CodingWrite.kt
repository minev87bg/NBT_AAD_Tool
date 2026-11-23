package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog

/** One step of a backup read or a coding write, reported plainly — never papered over, per [AGENTS.md]'s
 * "report faithfully": a failed step carries its actual UDS NRC, not a guess at what went wrong. */
data class WriteStep(val name: String, val ok: Boolean, val detail: String)

/** What reading the car's current coding (the mandatory step before any write) found. */
class BackupResult(val vin: String?, val mem: Map<Int, Int>?, val ncdText: String?, val steps: List<WriteStep>) {
    val ok: Boolean get() = mem != null
}

/** What a write (or a revert, which is just a write of the backup) actually did, step by step. */
class WriteOutcome(val steps: List<WriteStep>, val success: Boolean, val newNcdText: String?)

/**
 * Durable coding writes to the NBT EVO head unit: read a backup, edit specific bits, re-sign, and
 * write through the authenticated UDS flow — the choreography from
 * vandelayautollc/nbt-evo-toolkit's `capabilities/coding/03-write-flow.md` (MIT licensed), ported
 * here rather than invented. One specific that reference itself flags as unverified (not firmware-
 * traced, not yet live-confirmed on a real car) is carried over honestly rather than hidden: the exact
 * payload `CheckCodingSignature`'s positive response carries (only "positive vs. negative" is checked
 * here, same as the toolkit's own `write_coding.py`). The CPS (`0x37FE`) payload used to be a second
 * such placeholder (a single zero byte); confirmed on-car 2026-11-23 that this ECU wants its own VIN's
 * last 7 characters there instead - see the comment at that write site. Everything else — the signing
 * math, the L3 SecurityAccess handshake, the write order with the signature last, the ownership (VIN)
 * gate — is the part the toolkit's own self-test and on-car use back up, and is what this file leans on.
 */
object CodingWrite {
    /** Every user coding datagroup on the NBT EVO (CAFD 00001EF6), incl. CAF_ID (0x300F) and the signature (0x3010). */
    val BACKUP_DATAGROUPS: List<Int> = (0x3000..0x3010).toList()

    private fun describeOutcome(label: String, o: Hsfz.CodingSession.Outcome): WriteStep = when (o) {
        is Hsfz.CodingSession.Outcome.Positive -> WriteStep(label, true, "OK")
        is Hsfz.CodingSession.Outcome.Negative -> WriteStep(label, false, "rejected (NRC 0x%02X)".format(o.nrc))
        Hsfz.CodingSession.Outcome.NoResponse -> WriteStep(label, false, "no response")
    }

    private fun readVinFrom(session: Hsfz.CodingSession): Pair<String?, WriteStep> {
        val o = session.readDataByIdentifier(0xF190)
        val vin = (o as? Hsfz.CodingSession.Outcome.Positive)?.data
            ?.let { if (it.size > 3) String(it, 3, it.size - 3, Charsets.ISO_8859_1).trim('\u0000', ' ') else null }
        return vin to WriteStep("Read VIN", vin != null, vin ?: describeOutcome("", o).detail)
    }

    /**
     * Reads the VIN and every coding datagroup (`0x3000`-`0x3010`) straight from the head unit —
     * the same read the reference toolkit's `read_coding.py` does, and the mandatory first step
     * before any write. Read-only: no SecurityAccess, no key, nothing sent that changes the car.
     */
    fun readBackup(zgwIp: String): BackupResult {
        val steps = mutableListOf<WriteStep>()
        return try {
            Hsfz.CodingSession(zgwIp).use { session ->
                val (vin, vinStep) = readVinFrom(session)
                steps.add(vinStep)
                if (vin == null) return BackupResult(null, null, null, steps)

                val mem = mutableMapOf<Int, Int>()
                var anyDg = false
                for (dg in BACKUP_DATAGROUPS) {
                    val o = session.readDataByIdentifier(dg)
                    if (o is Hsfz.CodingSession.Outcome.Positive && o.data.size > 3) {
                        val page = o.data.copyOfRange(3, o.data.size)
                        for (i in page.indices) mem[(dg shl 8) or i] = page[i].toInt() and 0xFF
                        anyDg = true
                        steps.add(WriteStep("Read datagroup 0x%04X".format(dg), true, "${page.size} bytes"))
                    } else {
                        // Not every datagroup in the range need exist on this variant; read_coding.py skips those too.
                        steps.add(describeOutcome("Read datagroup 0x%04X".format(dg), o))
                    }
                }
                if (!anyDg) return BackupResult(vin, null, null, steps)
                val ncdText = Ncd.emit(mem, Ncd.buildRecords(mem, BACKUP_DATAGROUPS))
                BackupResult(vin, mem, ncdText, steps)
            }
        } catch (e: Exception) {
            AppLog.e("Coding", "Reading the coding backup failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
            BackupResult(null, null, null, steps)
        }
    }

    /**
     * Applies [edits] to [backupMem], re-signs locally with [ncdKey], and — only if that signature
     * verifies offline — writes it to the car: confirms the connected VIN is [ownVin], runs
     * SecurityAccess via [l3Key], writes every changed datagroup with the signature LAST, checks
     * `CheckCodingSignature`, writes the CPS, and hard-resets the head unit so it reboots and
     * re-verifies the new coding. Stops at the first failed step and reports it plainly; never
     * guesses whether a later step would have worked.
     */
    fun writeCoding(
        zgwIp: String,
        backupMem: Map<Int, Int>,
        edits: List<Pair<CodingBit, Int>>,
        ncdKey: NcdSigning.Key,
        l3Key: L3.Key,
        ownVin: String,
    ): WriteOutcome {
        val steps = mutableListOf<WriteStep>()
        val mem = backupMem.toMutableMap()
        for ((bit, value) in edits) {
            val shift = Integer.numberOfTrailingZeros(bit.mask)
            Ncd.setByte(mem, bit.datagroup, bit.byteOffset, value, bit.mask, shift)
        }

        val sig = try {
            NcdSigning.resign(mem, ncdKey)
        } catch (e: Exception) {
            steps.add(WriteStep("Sign locally", false, e.message ?: "failed"))
            return WriteOutcome(steps, false, null)
        }
        steps.add(WriteStep("Sign locally", true, "${sig.size}-byte signature"))
        if (!NcdSigning.verify(mem, ncdKey)) {
            steps.add(WriteStep("Verify signature locally", false, "does not verify — nothing was sent to the car"))
            return WriteOutcome(steps, false, null)
        }
        steps.add(WriteStep("Verify signature locally", true, "OK"))

        return try {
            Hsfz.CodingSession(zgwIp).use { session ->
                val (vin, vinStep) = readVinFrom(session)
                steps.add(vinStep)
                if (vin == null || !vin.equals(ownVin.trim(), ignoreCase = true)) {
                    steps.add(WriteStep("Confirm VIN", false, "car is ${vin ?: "unreadable"}, expected ${ownVin.trim()} — refusing to write"))
                    return WriteOutcome(steps, false, null)
                }
                steps.add(WriteStep("Confirm VIN", true, vin))

                // Best-effort: if an earlier attempt left the ECU in a non-default session (it only
                // times out on its own after a few seconds — closing our socket doesn't reset it),
                // a fresh "enter extended session" right after can be rejected with NRC 0x22
                // (conditionsNotCorrect). Ask for default session first; never gates on the answer.
                steps.add(describeOutcome("Return to default session", session.enterSession(0x01)))

                val ext = session.enterSession(0x03)
                steps.add(describeOutcome("Enter extended session", ext))
                if (ext !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                val coding = session.enterSession(0x41)
                steps.add(describeOutcome("Enter coding session", coding))
                if (coding !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                // Confirmed on-car via probeSecurityAccessLevels(): the bare 2-byte `27 01` this used to
                // send gets NRC 0x13 (format/length). This ECU wants a 4-byte testerId (00000000)
                // appended - see the comment on securityAccessSeedCoding().
                val seedOutcome = session.securityAccessSeedCoding()
                steps.add(describeOutcome("SecurityAccess: request seed", seedOutcome))
                val seed = (seedOutcome as? Hsfz.CodingSession.Outcome.Positive)?.data
                    ?.let { if (it.size >= 10) it.copyOfRange(2, 10) else null }
                if (seed == null) return WriteOutcome(steps, false, null)

                val sendKey = try {
                    L3.signSeed(seed, l3Key)
                } catch (e: Exception) {
                    steps.add(WriteStep("Compute L3 key response", false, e.message ?: "failed"))
                    return WriteOutcome(steps, false, null)
                }
                // Confirmed on-car: securityAccessSendKey(0x01, ...) reused requestSeed's own
                // subfunction byte and got NRC 0x13 - see the comment on securityAccessSendKeyCoding().
                val saOutcome = session.securityAccessSendKeyCoding(sendKey)
                steps.add(describeOutcome("SecurityAccess: send key", saOutcome))
                if (saOutcome !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                val present = mem.keys.map { it ushr 8 }.distinct().sorted()
                val order = present.filter { it != NcdSigning.SIGNATURE_DATAGROUP } +
                    (if (NcdSigning.SIGNATURE_DATAGROUP in present) listOf(NcdSigning.SIGNATURE_DATAGROUP) else emptyList())
                for (dg in order) {
                    val page = Ncd.pages(mem, listOf(dg))[dg] ?: continue
                    val w = session.writeDataByIdentifier(dg, page)
                    steps.add(describeOutcome("Write datagroup 0x%04X".format(dg), w))
                    if (w !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)
                }

                val check = session.routineControl(0x01, 0x0F01)
                steps.add(describeOutcome("CheckCodingSignature", check))
                if (check !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                // CPS (0x37FE): confirmed on-car 2026-11-23 that the single-zero-byte placeholder this
                // used to send gets NRC 0x13 (incorrectMessageLengthOrInvalidFormat) - and the read-back
                // taken right after showed 0x37FE still held 7 ASCII bytes ("C233076") matching this
                // car's own VIN's last 7 characters exactly. So the correct payload is the VIN's own
                // last 7 characters, not an invented value - writing it back re-asserts the car's
                // identity against the signature just written, rather than changing anything.
                val cps = vin.takeLast(7).toByteArray(Charsets.ISO_8859_1)
                steps.add(describeOutcome("Write CPS", session.writeDataByIdentifier(0x37FE, cps)))
                steps.add(describeOutcome("Read back CPS", session.readDataByIdentifier(0x37FE)))
                steps.add(describeOutcome("Reset head unit", session.ecuReset()))

                val ncdText = Ncd.emit(mem, Ncd.buildRecords(mem, BACKUP_DATAGROUPS))
                WriteOutcome(steps, true, ncdText)
            }
        } catch (e: Exception) {
            AppLog.e("Coding", "Writing coding failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
            WriteOutcome(steps, false, null)
        }
    }

    /** Reverts to [backupMem] — already correctly signed, since it's what the car gave you — the same way: a signed write. */
    fun revertCoding(zgwIp: String, backupMem: Map<Int, Int>, ncdKey: NcdSigning.Key, l3Key: L3.Key, ownVin: String): WriteOutcome =
        writeCoding(zgwIp, backupMem, emptyList(), ncdKey, l3Key, ownVin)

    /** "Clear all DTCs", broadcast to the whole car — not part of the signed write, no key needed, its own standalone
     * action run once the head unit a write or revert just reset is back. See [Hsfz.clearAllCarFaults]. */
    fun clearAllCarFaults(zgwIp: String): List<WriteStep> = Hsfz.clearAllCarFaults(zgwIp)

    /** "Clear DTC" narrowed to the head unit alone, point-to-point — see [Hsfz.clearHeadUnitFaults]. */
    fun clearHeadUnitFaults(zgwIp: String): List<WriteStep> = Hsfz.clearHeadUnitFaults(zgwIp)

    /** How many DTCs the head unit itself currently has stored — see [Hsfz.readHeadUnitDtcCount]. */
    fun headUnitDtcCount(zgwIp: String): Int? = Hsfz.readHeadUnitDtcCount(zgwIp)

    /** Odd (requestSeed) SecurityAccess levels worth trying when level 0x01 is rejected: common BMW
     * write-unlock levels, plus 0x11 (the flash level - proven on-car in the reference project) as a
     * sanity check that SecurityAccess works *at all* in this coding session. */
    private val PROBE_LEVELS = listOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x0D, 0x1D, 0x1F, 0x21, 0x11)

    /**
     * READ-ONLY: enters the coding session and tries SecurityAccess requestSeed at each of
     * [PROBE_LEVELS], one after another. Never calls sendKey, so no key is ever submitted and nothing
     * is unlocked or written — a rejected or accepted seed changes nothing on the car either way.
     * Reports every level tried, so whichever one answers with a real seed (`67 <level> <seed>`)
     * instead of a negative response is visible directly, rather than guessed at.
     */
    fun probeSecurityAccessLevels(zgwIp: String, ownVin: String): List<WriteStep> {
        val steps = mutableListOf<WriteStep>()
        try {
            Hsfz.CodingSession(zgwIp).use { session ->
                val (vin, vinStep) = readVinFrom(session)
                steps.add(vinStep)
                if (vin == null || !vin.equals(ownVin.trim(), ignoreCase = true)) {
                    steps.add(WriteStep("Confirm VIN", false, "car is ${vin ?: "unreadable"}, expected ${ownVin.trim()} — stopping"))
                    return steps
                }
                steps.add(WriteStep("Confirm VIN", true, vin))

                // Best-effort, see the same step in writeCoding(): clears a session an earlier
                // attempt (this probe included) may have left the ECU in.
                steps.add(describeOutcome("Return to default session", session.enterSession(0x01)))

                val ext = session.enterSession(0x03)
                steps.add(describeOutcome("Enter extended session", ext))
                if (ext !is Hsfz.CodingSession.Outcome.Positive) return steps

                val coding = session.enterSession(0x41)
                steps.add(describeOutcome("Enter coding session", coding))
                if (coding !is Hsfz.CodingSession.Outcome.Positive) return steps

                var levelOneWasFormatIssue = false
                for (level in PROBE_LEVELS) {
                    val label = "Request seed, level 0x%02X".format(level)
                    val step = when (val o = session.securityAccessSeed(level)) {
                        is Hsfz.CodingSession.Outcome.Positive -> {
                            val seed = if (o.data.size > 2) o.data.copyOfRange(2, o.data.size) else ByteArray(0)
                            WriteStep(label, true, "seed: ${seed.joinToString(" ") { "%02X".format(it) }}")
                        }
                        is Hsfz.CodingSession.Outcome.Negative -> WriteStep(label, false, "rejected (NRC 0x%02X)".format(o.nrc))
                        Hsfz.CodingSession.Outcome.NoResponse -> WriteStep(label, false, "no response")
                    }
                    steps.add(step)
                    // NRC 0x12 (subFunctionNotSupported) means this level doesn't exist here at all.
                    // NRC 0x13 (incorrectMessageLengthOrInvalidFormat) on level 0x01 specifically means
                    // the ECU *does* recognize 0x01 as a real level - it just didn't like the length or
                    // shape of our 2-byte request. Those are different answers; only the second is
                    // worth following up.
                    if (level == 0x01 && step.detail.contains("0x13")) levelOneWasFormatIssue = true
                }

                // docs/keys.md's own seed->key construction folds in "the 4-byte testerId sent with
                // requestSeed (00000000)" - worded as if that value is part of the request on the
                // wire, not just an input to the later key math. If level 0x01 is real but our bare
                // `27 01` is rejected for length/format, trying it with that testerId appended (and a
                // few shorter/longer paddings in case the exact count differs) tells us directly
                // whether that's the missing piece, instead of guessing further.
                if (levelOneWasFormatIssue) {
                    for (extraLen in 1..8) {
                        val uds = byteArrayOf(0x27, 0x01) + ByteArray(extraLen)
                        val label = "Request seed, level 0x01 + $extraLen zero byte(s)"
                        val step = when (val o = session.exchange(uds)) {
                            is Hsfz.CodingSession.Outcome.Positive -> {
                                val seed = if (o.data.size > 2) o.data.copyOfRange(2, o.data.size) else ByteArray(0)
                                WriteStep(label, true, "seed: ${seed.joinToString(" ") { "%02X".format(it) }}")
                            }
                            is Hsfz.CodingSession.Outcome.Negative -> WriteStep(label, false, "rejected (NRC 0x%02X)".format(o.nrc))
                            Hsfz.CodingSession.Outcome.NoResponse -> WriteStep(label, false, "no response")
                        }
                        steps.add(step)
                        if (step.ok) break
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Coding", "Probing SecurityAccess levels failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
        }
        return steps
    }
}
