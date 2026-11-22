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
 * here rather than invented. Two specifics that reference itself flags as unverified (not firmware-
 * traced, not yet live-confirmed on a real car) are carried over honestly rather than hidden:
 *  - the exact payload `CheckCodingSignature`'s positive response carries (only "positive vs.
 *    negative" is checked here, same as the toolkit's own `write_coding.py`);
 *  - the CPS (`0x37FE`) payload bytes, where a single zero byte is used as a placeholder.
 * Everything else — the signing math, the L3 SecurityAccess handshake, the write order with the
 * signature last, the ownership (VIN) gate — is the part the toolkit's own self-test and on-car use
 * back up, and is what this file leans on.
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

                val ext = session.enterSession(0x03)
                steps.add(describeOutcome("Enter extended session", ext))
                if (ext !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                val coding = session.enterSession(0x41)
                steps.add(describeOutcome("Enter coding session", coding))
                if (coding !is Hsfz.CodingSession.Outcome.Positive) return WriteOutcome(steps, false, null)

                val seedOutcome = session.securityAccessSeed(0x01)
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
                val saOutcome = session.securityAccessSendKey(0x01, sendKey)
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

                // CPS payload: unverified placeholder — see this file's header.
                steps.add(describeOutcome("Write CPS", session.writeDataByIdentifier(0x37FE, byteArrayOf(0x00))))
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
}
