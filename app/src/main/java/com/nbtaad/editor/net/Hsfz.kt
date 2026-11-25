package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Minimal BMW HSFZ (the protocol ISTA / E-Sys / Tool32 use over ENET).
 *
 *  - UDP 6811: send 00 00 00 00 00 11 to the broadcast address, the ZGW answers with a text like
 *              "DIAGADR10BMWMAC<12 hex>BMWVIN<17 chars>"; the sender of the answer is the ZGW.
 *  - TCP 6801: frames of  [len:4 BE][control word:2][payload]. Control word 0x0001 = diagnostic message,
 *              payload = [source][target][UDS bytes]. Control word 0x0002 = acknowledge.
 *  - Head unit (HU_NBT) has diagnostic address 0x63, tester address is 0xF4, ZGW is 0x10.
 *  - "ECU reset" = UDS 11 01 to 0x63 (same as Tool32 NBTEVO.PRG job STEUERGERAETE_RESET).
 */
object Hsfz {
    const val TCP_PORT = 6801
    const val UDP_PORT = 6811

    private const val TESTER = 0xF4
    private const val ZGW = 0x10
    private const val HEAD_UNIT = 0x63

    // "Clear all DTCs" (clearAllCarFaults, below) needs different addressing from everything else in
    // this file: E-Sys's own Transmitter tool macro targets 0xDF so every ECU on the bus reacts, not
    // just the head unit - and, confirmed by the car's owner testing that same tool, only answers at
    // all when the source/tester address is 0xF5, not the usual 0xF4. Neither address is explained
    // anywhere; both are simply what made the car actually react.
    private const val TESTER_BROADCAST = 0xF5
    private const val BROADCAST_ALL = 0xDF

    private const val CW_DIAG = 0x0001
    private const val CW_ACK = 0x0002

    val IDENT_REQUEST = byteArrayOf(0, 0, 0, 0, 0, 0x11)

    data class Ident(val ip: String, val vin: String?, val mac: String?, val diagAddr: String?)

    fun parseIdent(ip: String, data: ByteArray, len: Int): Ident? {
        val text = String(data, 0, len, Charsets.ISO_8859_1)
        if (!text.contains("DIAGADR") && !text.contains("BMWVIN")) return null
        val vin = Regex("BMWVIN([A-Za-z0-9]{17})").find(text)?.groupValues?.get(1)
            ?.uppercase()?.takeIf { isValidVin(it) }
        val mac = Regex("BMWMAC([0-9A-Fa-f]{12})").find(text)?.groupValues?.get(1)
        val diag = Regex("DIAGADR(\\d+)").find(text)?.groupValues?.get(1)
        return Ident(ip, vin, mac, diag)
    }

    private val VIN_RE = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** A real VIN: 17 characters, digits and capital letters except I, O and Q. */
    fun isValidVin(v: String): Boolean = VIN_RE.matches(v)

    private fun diagFrame(src: Int, dst: Int, uds: ByteArray): ByteArray {
        val payloadLen = 2 + uds.size
        val out = ByteArray(6 + payloadLen)
        out[0] = (payloadLen ushr 24).toByte()
        out[1] = (payloadLen ushr 16).toByte()
        out[2] = (payloadLen ushr 8).toByte()
        out[3] = payloadLen.toByte()
        out[4] = (CW_DIAG ushr 8).toByte()
        out[5] = CW_DIAG.toByte()
        out[6] = src.toByte()
        out[7] = dst.toByte()
        uds.copyInto(out, 8)
        return out
    }

    private class Frame(val type: Int, val payload: ByteArray)

    private fun ByteArray.toHexSpaced(): String = joinToString(" ") { "%02X".format(it) }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = try {
                input.read(buf, off, buf.size - off)
            } catch (e: SocketTimeoutException) {
                return false
            }
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readFrame(input: InputStream): Frame? {
        val h = ByteArray(6)
        if (!readFully(input, h)) return null
        val len = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        if (len < 0 || len > 65536) return null
        val p = ByteArray(len)
        if (len > 0 && !readFully(input, p)) return null
        val type = ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
        return Frame(type, p)
    }

    /**
     * Reads one coding datagroup's page of bytes from the head unit (UDS `22 <dg>`, plain read, no SecurityAccess —
     * the same call the project's own coding-reading tools use). Null when the gateway doesn't answer, the head unit
     * doesn't have that datagroup, or anything else goes wrong; never throws.
     */
    fun readCodingDatagroup(zgwIp: String, datagroup: Int): ByteArray? {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 2000)
                s.soTimeout = 2500
                val dgHi = (datagroup ushr 8).toByte()
                val dgLo = datagroup.toByte()
                val uds = byteArrayOf(0x22, dgHi, dgLo)
                s.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, uds))
                s.getOutputStream().flush()
                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    val f = readFrame(s.getInputStream()) ?: break
                    if (f.type != CW_DIAG || f.payload.size < 3) continue
                    val uresp = f.payload.copyOfRange(2, f.payload.size) // drop src, dst
                    when (uresp[0].toInt() and 0xFF) {
                        0x62 -> {
                            if (uresp.size >= 3 && uresp[1] == dgHi && uresp[2] == dgLo) {
                                return uresp.copyOfRange(3, uresp.size)
                            }
                        }
                        0x7F -> {
                            val nrc = if (uresp.size >= 3) uresp[2].toInt() and 0xFF else -1
                            if (nrc == 0x78) continue // response pending, keep waiting
                            AppLog.d("HSFZ", "Reading datagroup 0x%04X: rejected (0x%02X)".format(datagroup, nrc))
                            return null
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLog.d("HSFZ", "Reading datagroup 0x%04X from $zgwIp failed: ${e.javaClass.simpleName}: ${e.message}".format(datagroup))
            null
        }
    }

    /**
     * How many DTCs the head unit currently has stored (UDS `19 01 FF` - reportNumberOfDTCByStatusMask,
     * any status bit set), as a quick before/after around a coding write or revert. Scoped to the one
     * ECU this app already talks to one-on-one - not "the whole car" the way [clearAllCarFaults] is;
     * null if it can't be read, which is not treated as zero.
     */
    fun readHeadUnitDtcCount(zgwIp: String): Int? {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 2000)
                s.soTimeout = 2000
                s.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, byteArrayOf(0x19, 0x01, 0xFF.toByte())))
                s.getOutputStream().flush()
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val f = readFrame(s.getInputStream()) ?: break
                    if (f.type != CW_DIAG || f.payload.size < 3) continue
                    val resp = f.payload.copyOfRange(2, f.payload.size)
                    if (resp.isEmpty()) continue
                    when (resp[0].toInt() and 0xFF) {
                        0x59 -> if (resp.size >= 6 && resp[1] == 0x01.toByte()) {
                            return ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
                        }
                        0x7F -> {
                            val nrc = if (resp.size >= 3) resp[2].toInt() and 0xFF else -1
                            if (nrc == 0x78) continue
                            AppLog.d("HSFZ", "Reading the head unit's DTC count: rejected (0x%02X)".format(nrc))
                            return null
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLog.d("HSFZ", "Reading the head unit's DTC count from $zgwIp failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * "Clear all DTCs": E-Sys's own Transmitter tool macro for clearing fault memory across the whole
     * car in one shot, not just the head unit - five UDS requests, in order: ClearDiagnosticInformation
     * (all groups), then three BMW-specific routine-control steps (InfoSpeicherLoeschen - a separate
     * "info memory" of its own - then ZFS-DM Lock/Clear/Unlock), each broadcast to every ECU on the bus
     * at once (target [BROADCAST_ALL], not the head unit's own [HEAD_UNIT]) using [TESTER_BROADCAST] as
     * the source address - see the comment on those two constants for why.
     *
     * A broadcast request has no single guaranteed answer: several ECUs, one, or none may reply, and no
     * reply at all is the normal case here, not a failure - only an explicit negative response (0x7F) is
     * reported as one. Call only once the head unit the write/revert just reset has actually come back
     * (it has to be listening on the bus again for its own fault memory to react to this).
     */
    fun clearAllCarFaults(zgwIp: String): List<WriteStep> {
        val macro = listOf(
            "Clear DTC" to byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            "Clear info memory" to byteArrayOf(0x31, 0x01, 0x0F, 0x06),
            "ZFS: lock" to byteArrayOf(0x31, 0x01, 0x40, 0x00, 0x00),
            "ZFS: clear" to byteArrayOf(0x31, 0x01, 0x40, 0x00, 0x05),
            "ZFS: unlock" to byteArrayOf(0x31, 0x01, 0x40, 0x00, 0x01),
        )
        val steps = mutableListOf<WriteStep>()
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 3000)
                s.soTimeout = 1200
                for ((label, uds) in macro) {
                    AppLog.d("HSFZ", "-> (broadcast) ${uds.toHexSpaced()}")
                    steps.add(sendClearStep(s, TESTER_BROADCAST, BROADCAST_ALL, label, uds, expectReply = false))
                }
            }
        } catch (e: Exception) {
            AppLog.e("HSFZ", "Clearing all vehicle faults failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
        }
        return steps
    }

    /**
     * Clears just the head unit's own fault memory - the same two core UDS requests [clearAllCarFaults]
     * sends (ClearDiagnosticInformation, then BMW's own "info memory" routine-control step), but addressed
     * directly to [HEAD_UNIT] (point-to-point, not broadcast) and without that macro's extra ZFS-DM
     * lock/clear/unlock steps, since those are a vehicle-wide energy-state dance, not something a single
     * ECU's own DTC clear needs.
     */
    fun clearHeadUnitFaults(zgwIp: String): List<WriteStep> {
        val macro = listOf(
            "Clear DTC" to byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            "Clear info memory" to byteArrayOf(0x31, 0x01, 0x0F, 0x06),
        )
        val steps = mutableListOf<WriteStep>()
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 3000)
                s.soTimeout = 1200
                for ((label, uds) in macro) {
                    AppLog.d("HSFZ", "-> ${uds.toHexSpaced()}")
                    steps.add(sendClearStep(s, TESTER, HEAD_UNIT, label, uds, expectReply = true))
                }
            }
        } catch (e: Exception) {
            AppLog.e("HSFZ", "Clearing the head unit's own faults failed", e)
            steps.add(WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
        }
        return steps
    }

    /**
     * Sends one UDS request and waits for its real response, extending the wait on NRC 0x78 ("still
     * working") instead of treating it as a rejection - this ECU acks almost everything with 0x78 before
     * the real answer, so without this every clear would misreport as failed. [expectReply] only changes
     * the "nothing came back" verdict's wording: true for a normal point-to-point request (a head unit
     * that never answers at all is worth a clear "no reply" note), false for a broadcast one, where no
     * reply is the ordinary case and not a sign anything went wrong.
     */
    private fun sendClearStep(s: Socket, src: Int, dst: Int, label: String, uds: ByteArray, expectReply: Boolean): WriteStep {
        s.getOutputStream().write(diagFrame(src, dst, uds))
        s.getOutputStream().flush()
        var negativeNrc: Int? = null
        var sawPositive = false
        var deadline = System.currentTimeMillis() + 1200
        val hardDeadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val f = readFrame(s.getInputStream()) ?: break
            if (f.type != CW_DIAG || f.payload.size < 3) continue
            val resp = f.payload.copyOfRange(2, f.payload.size)
            if (resp.isEmpty()) continue
            if ((resp[0].toInt() and 0xFF) == 0x7F) {
                val nrc = if (resp.size >= 3) resp[2].toInt() and 0xFF else -1
                if (nrc == 0x78 && System.currentTimeMillis() < hardDeadline) {
                    deadline = System.currentTimeMillis() + 1200
                    continue
                }
                negativeNrc = nrc
            } else {
                sawPositive = true
            }
        }
        return when {
            negativeNrc != null -> WriteStep(label, false, "rejected (NRC 0x%02X)".format(negativeNrc))
            sawPositive -> WriteStep(label, true, "OK")
            expectReply -> WriteStep(label, false, "no reply")
            else -> WriteStep(label, true, "sent (broadcast - no reply expected)")
        }
    }

    /** Reads the VIN from the ZGW (UDS 22 F190). Used only when the UDP discovery gave no VIN. */
    fun readVin(zgwIp: String): String? {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 2000)
                s.soTimeout = 2500
                val uds = byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())
                s.getOutputStream().write(diagFrame(TESTER, ZGW, uds))
                s.getOutputStream().flush()
                repeat(4) {
                    val f = readFrame(s.getInputStream()) ?: return null
                    if (f.type == CW_DIAG) {
                        val p = f.payload
                        for (i in 0 until p.size - 3 - 17 + 1) {
                            if (p[i] == 0x62.toByte() && p[i + 1] == 0xF1.toByte() && p[i + 2] == 0x90.toByte()) {
                                val v = String(p, i + 3, 17, Charsets.ISO_8859_1).uppercase()
                                if (isValidVin(v)) return v
                            }
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLog.d("HSFZ", "Reading the VIN from $zgwIp failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * What the reset got: [ok], a text for the person, and [unreachable] when not even a connection to the gateway could
     * be made (then nothing was sent, so trying again, possibly at another address, is safe).
     */
    class ResetResult(val ok: Boolean, val message: String, val unreachable: Boolean = false)

    /** Sends "ECU reset" to the head unit through the ZGW. No SSH needed. */
    fun resetHeadUnit(zgwIp: String): ResetResult {
        AppLog.i("HSFZ", "Reset: connecting to $zgwIp:$TCP_PORT")
        val r = sendReset(zgwIp)
        AppLog.i("HSFZ", "Reset result: ok=${r.ok}, unreachable=${r.unreachable}, \"${r.message}\"")
        return r
    }

    private fun sendReset(zgwIp: String): ResetResult {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            try {
                s.connect(InetSocketAddress(zgwIp, TCP_PORT), 3000)
            } catch (e: IOException) {
                AppLog.w("HSFZ", "Reset: no connection to $zgwIp:$TCP_PORT: ${e.javaClass.simpleName}: ${e.message}")
                return ResetResult(false, "The vehicle's gateway ($zgwIp) does not answer.", unreachable = true)
            }
            s.soTimeout = 3000
            s.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, byteArrayOf(0x11, 0x01)))
            s.getOutputStream().flush()

            var gotAck = false
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val f = readFrame(s.getInputStream()) ?: break
                if (f.type == CW_ACK) {
                    gotAck = true
                    continue
                }
                if (f.type == CW_DIAG && f.payload.size >= 3) {
                    val uds = f.payload.copyOfRange(2, f.payload.size)
                    when (uds[0].toInt() and 0xFF) {
                        0x51 -> return ResetResult(true, "Head unit accepted the reset command.")
                        0x7F -> {
                            val nrc = if (uds.size >= 3) uds[2].toInt() and 0xFF else -1
                            if (nrc == 0x78) continue // response pending
                            return ResetResult(false, "Head unit rejected the reset (error code 0x%02X).".format(nrc))
                        }
                    }
                }
            }
            return if (gotAck) ResetResult(true, "Reset command sent (gateway acknowledged it).")
            else ResetResult(false, "The gateway did not answer the reset command.")
        } catch (e: Exception) {
            AppLog.e("HSFZ", "Reset failed", e)
            return ResetResult(false, "Reset failed: ${e.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * One persistent HSFZ session to the head unit, for the handful of UDS exchanges a coding write
     * needs **in order** — session control, SecurityAccess, the per-datagroup writes, the signature
     * check, the CPS, then the reset ([capabilities/coding/03-write-flow.md] in
     * vandelayautollc/nbt-evo-toolkit). Unlike [readVin]/[resetHeadUnit]/[readCodingDatagroup], which
     * each open and close their own socket for one exchange, these have to share a session because
     * SecurityAccess only unlocks the connection it was granted on. The caller must [close] it (it
     * implements [AutoCloseable], so `use { }` works).
     */
    class CodingSession(zgwIp: String) : java.io.Closeable {
        private val socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(zgwIp, TCP_PORT), 3000)
            soTimeout = 4000
        }

        sealed class Outcome {
            /** A positive UDS response, SID echo included (e.g. `62 <did> <data...>`, `6E <did>`, `67 01 <seed>`). */
            data class Positive(val data: ByteArray) : Outcome()
            data class Negative(val nrc: Int) : Outcome()
            object NoResponse : Outcome()
        }

        /** Sends one UDS request and returns its outcome, riding response-pending (`7F xx 78`) until [timeoutMs]. */
        fun exchange(uds: ByteArray, timeoutMs: Long = 5000): Outcome {
            val requestSid = uds[0].toInt() and 0xFF
            AppLog.d("HSFZ", "-> ${uds.toHexSpaced()}")
            socket.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, uds))
            socket.getOutputStream().flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val f = readFrame(socket.getInputStream()) ?: return Outcome.NoResponse
                AppLog.d("HSFZ", "<- type=0x%04X %s".format(f.type, f.payload.toHexSpaced()))
                if (f.type != CW_DIAG || f.payload.size < 3) continue
                val resp = f.payload.copyOfRange(2, f.payload.size)
                if (resp.isEmpty()) continue
                val sid = resp[0].toInt() and 0xFF
                when {
                    sid == 0x7F -> {
                        val echoedSid = if (resp.size >= 2) resp[1].toInt() and 0xFF else -1
                        if (echoedSid != requestSid) {
                            // Not a negative response to *this* request (stray/late frame for a
                            // different one) - don't misattribute its NRC to us. Keep waiting.
                            AppLog.w(
                                "HSFZ",
                                "ignoring 0x7F for SID 0x%02X while waiting on 0x%02X".format(echoedSid, requestSid),
                            )
                            continue
                        }
                        val nrc = if (resp.size >= 3) resp[2].toInt() and 0xFF else -1
                        if (nrc == 0x78) continue // responsePending: the ECU is still working, keep waiting
                        return Outcome.Negative(nrc)
                    }
                    sid == requestSid + 0x40 -> return Outcome.Positive(resp)
                    else -> continue // the gateway's echo of our own request, or an unrelated frame
                }
            }
            return Outcome.NoResponse
        }

        fun enterSession(sub: Int): Outcome = exchange(byteArrayOf(0x10, sub.toByte()))
        fun securityAccessSeed(level: Int): Outcome = exchange(byteArrayOf(0x27, level.toByte()))

        /**
         * Coding-level (0x01) requestSeed, confirmed on-car 2026-11-23: a bare `27 01` gets NRC 0x13
         * (incorrectMessageLengthOrInvalidFormat) - this ECU wants a 4-byte testerId appended, matching
         * docs/keys.md's "4-byte testerId sent with requestSeed (00000000)" wording literally rather than
         * only as an input to the later key math. `27 01 00 00 00 00` gets back `67 01 <8-byte seed>`.
         * [testerId] defaults to all-zero per that doc; only override it if a car is ever found that wants
         * something else here.
         */
        fun securityAccessSeedCoding(testerId: ByteArray = ByteArray(4)): Outcome =
            exchange(byteArrayOf(0x27, 0x01) + testerId)

        /**
         * Matching sendKey for securityAccessSeedCoding(). Confirmed on-car 2026-11-23: the previous
         * version of this call sent `27 01 <key>` - reusing requestSeed's own subfunction byte - and got
         * NRC 0x13. The raw hex showed a 134-byte message tagged with subfunction 0x01, which the ECU
         * validates against requestSeed's fixed 6-byte shape and rejects on length, never even looking at
         * the key. Per ISO 14229 (and the reference project's own 03-write-flow.md: `27 02 <key> -> 67
         * 02`), sendKey's subfunction is requestSeed's + 1 - 0x02 here, not 0x01.
         */
        fun securityAccessSendKeyCoding(key: ByteArray): Outcome =
            exchange(byteArrayOf(0x27, 0x02) + key, timeoutMs = 6000)
        fun readDataByIdentifier(did: Int): Outcome =
            exchange(byteArrayOf(0x22, (did ushr 8).toByte(), did.toByte()))
        fun writeDataByIdentifier(did: Int, data: ByteArray): Outcome =
            exchange(byteArrayOf(0x2E, (did ushr 8).toByte(), did.toByte()) + data, timeoutMs = 6000)
        fun routineControl(sub: Int, rid: Int, data: ByteArray = ByteArray(0)): Outcome =
            exchange(byteArrayOf(0x31, sub.toByte(), (rid ushr 8).toByte(), rid.toByte()) + data)
        fun ecuReset(sub: Int = 0x01): Outcome = exchange(byteArrayOf(0x11, sub.toByte()), timeoutMs = 8000)

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /**
     * One HSFZ session for a UDS **flash** (not coding) operation: entering/leaving programming mode,
     * the flash-level (0x11/0x12) SecurityAccess handshake, and the erase/download/transfer/exit/check
     * sequence that actually writes a file to the head unit's filesystem. Ported frame-for-frame from
     * vandelayautollc/nbt-evo-toolkit's `capabilities/flash_and_patching/tools/flash_plan.py` and
     * `03-flashing-safely.md` (MIT licensed) - "proven on-car: the OPT_DEBUG file-copy flash was run
     * over the UDS route (in both a Pi tool and the Android app)". See [FlashWrite] for the orchestration
     * (ordering, retries, the mandatory teardown) that calls these.
     *
     * Unlike [CodingSession], this session's own socket is **replaced**, not just held: `10 02`
     * (ProgrammingSession) makes the head unit self-reboot into its bootloader, which drops the TCP
     * connection - [reconnect] closes the old socket and opens a fresh one to the same address, which
     * [FlashWrite] uses to resume polling across that reboot (and again on the way back out).
     */
    class FlashSession(private val zgwIp: String) : java.io.Closeable {
        private var socket: Socket = openSocket(zgwIp)

        private fun openSocket(ip: String): Socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(ip, TCP_PORT), 3000)
            soTimeout = 4000
        }

        /** Closes the current socket and opens a new one to the same gateway - needed right after
         * `10 02` (ProgrammingSession) and again after `31 01 100F` (ActivateApplicationMode), since the
         * head unit reboots across both of those and the old TCP connection does not survive it. */
        fun reconnect() {
            runCatching { socket.close() }
            socket = openSocket(zgwIp)
        }

        sealed class Outcome {
            /** A positive UDS response, SID echo included (e.g. `67 11 <seed>`, `74 <len><maxBlk>`, `71 <sub><rid>`). */
            data class Positive(val data: ByteArray) : Outcome()
            data class Negative(val nrc: Int) : Outcome()
            object NoResponse : Outcome()
        }

        /** Sends one UDS request and returns its outcome, riding response-pending (`7F xx 78`) until [timeoutMs].
         * Same frame/retry logic as [CodingSession.exchange] - duplicated rather than shared because this
         * session's socket is a `var` (it gets replaced by [reconnect]), where [CodingSession]'s never is. */
        fun exchange(uds: ByteArray, timeoutMs: Long = 5000): Outcome {
            val requestSid = uds[0].toInt() and 0xFF
            AppLog.d("HSFZ", "-> ${uds.toHexSpaced()}")
            try {
                socket.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, uds))
                socket.getOutputStream().flush()
            } catch (e: IOException) {
                AppLog.w("HSFZ", "flash: write failed: ${e.javaClass.simpleName}: ${e.message}")
                return Outcome.NoResponse
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val f = try {
                    readFrame(socket.getInputStream())
                } catch (e: IOException) {
                    null
                } ?: return Outcome.NoResponse
                AppLog.d("HSFZ", "<- type=0x%04X %s".format(f.type, f.payload.toHexSpaced()))
                if (f.type != CW_DIAG || f.payload.size < 3) continue
                val resp = f.payload.copyOfRange(2, f.payload.size)
                if (resp.isEmpty()) continue
                val sid = resp[0].toInt() and 0xFF
                when {
                    sid == 0x7F -> {
                        val echoedSid = if (resp.size >= 2) resp[1].toInt() and 0xFF else -1
                        if (echoedSid != requestSid) {
                            AppLog.w("HSFZ", "ignoring 0x7F for SID 0x%02X while waiting on 0x%02X".format(echoedSid, requestSid))
                            continue
                        }
                        val nrc = if (resp.size >= 3) resp[2].toInt() and 0xFF else -1
                        if (nrc == 0x78) continue // responsePending - erase/checkMemory can hold this a long time
                        return Outcome.Negative(nrc)
                    }
                    sid == requestSid + 0x40 -> return Outcome.Positive(resp)
                    else -> continue
                }
            }
            return Outcome.NoResponse
        }

        /** Fire-and-forget `3E 80` (TesterPresent, suppressPosRspMsgIndicationBit set - no reply expected).
         * Call roughly once a second while a long step (transferData in particular) is in flight, so the
         * ECU doesn't time out the session for inactivity mid-write. Never throws. */
        fun testerPresent() {
            try {
                socket.getOutputStream().write(diagFrame(TESTER, HEAD_UNIT, byteArrayOf(0x3E, 0x80.toByte())))
                socket.getOutputStream().flush()
            } catch (_: IOException) {
                // best-effort; a dropped keepalive during a reboot window is expected, not an error
            }
        }

        fun enterSession(sub: Int, timeoutMs: Long = 5000): Outcome = exchange(byteArrayOf(0x10, sub.toByte()), timeoutMs)
        fun readDataByIdentifier(did: Int, timeoutMs: Long = 5000): Outcome =
            exchange(byteArrayOf(0x22, (did ushr 8).toByte(), did.toByte()), timeoutMs)
        fun writeDataByIdentifier(did: Int, data: ByteArray, timeoutMs: Long = 5000): Outcome =
            exchange(byteArrayOf(0x2E, (did ushr 8).toByte(), did.toByte()) + data, timeoutMs)
        fun routineControl(sub: Int, rid: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 5000): Outcome =
            exchange(byteArrayOf(0x31, sub.toByte(), (rid ushr 8).toByte(), rid.toByte()) + data, timeoutMs)
        fun ecuReset(sub: Int = 0x01, timeoutMs: Long = 8000): Outcome = exchange(byteArrayOf(0x11, sub.toByte()), timeoutMs)

        /** `85 <sub>` - ControlDTCSetting. `sub=0x02` turns DTC logging off, `0x01` back on. */
        fun dtcControl(sub: Int): Outcome = exchange(byteArrayOf(0x85.toByte(), sub.toByte()))

        /** `28 <sub> <commType>` - CommunicationControl. `sub=0x01` ("quiet") disables normal transmit
         * during the write; `sub=0x00` restores it. `commType=0x01` (normalCommunicationMessages) both ways. */
        fun commControl(sub: Int, commType: Int = 0x01): Outcome = exchange(byteArrayOf(0x28, sub.toByte(), commType.toByte()))

        /** `27 11` - flash-level requestSeed. Unlike [CodingSession.securityAccessSeedCoding], `flash_plan.py`
         * shows this sent bare (no testerId suffix) - not yet confirmed on this car whether that holds here
         * too; if `27 11` comes back NRC 0x13 the way coding's bare `27 01` did, the same 4-byte-testerId
         * fix is the first thing to try (see [FlashWrite]). */
        fun securityAccessSeedFlash(): Outcome = exchange(byteArrayOf(0x27, 0x11))

        /** `27 12 <key>` - flash-level sendKey. [key] is the full wire payload from [L3.signSeed] (the
         * `00000020` word-count prefix plus the 128-byte, per-word-swapped signature - 132 bytes total),
         * not a plain signed seed. */
        fun securityAccessSendKeyFlash(key: ByteArray): Outcome = exchange(byteArrayOf(0x27, 0x12) + key, timeoutMs = 6000)

        /** `34 00 44 <addr:4 BE><size:4 BE>` - requestDownload. [addr] is always 0 for a file-copy
         * container (there's no real flash address; the file-copy job ignores it). Reply is `74
         * <lengthFormatId><maxNumberOfBlockLength>` - the per-block payload size is that minus 2 (the
         * `36 <BSC>` header each transferData block also carries). */
        fun requestDownload(size: Int, addr: Int = 0): Outcome {
            val a = ByteArray(4); val sz = ByteArray(4)
            for (i in 0..3) { a[i] = (addr ushr (8 * (3 - i))).toByte(); sz[i] = (size ushr (8 * (3 - i))).toByte() }
            return exchange(byteArrayOf(0x34, 0x00, 0x44) + a + sz, timeoutMs = 8000)
        }

        /** `36 <BSC><block>` - transferData. [bsc] (blockSequenceCounter) starts at 1 and wraps `0xFF`->`0x00`;
         * each call is one block of up to `maxNumberOfBlockLength - 2` bytes. A positive reply is `76 <bsc>`,
         * echoing the same counter. */
        fun transferData(bsc: Int, block: ByteArray, timeoutMs: Long = 8000): Outcome =
            exchange(byteArrayOf(0x36, bsc.toByte()) + block, timeoutMs)

        /** `37` - requestTransferExit. The file-copy job actually runs when this is answered; nothing is
         * written to the car before this returns positive. */
        fun requestTransferExit(timeoutMs: Long = 15000): Outcome = exchange(byteArrayOf(0x37), timeoutMs)

        /** `31 01 FF00 02 44 <lbn:4 BE> 06` - eraseMemory (indicated form). `LBN 0` is the value proven
         * on-car for a file-copy container (a real flash-area erase would use its own LBN). Can emit `0x78`
         * for a long time - give this a generous [timeoutMs]. */
        fun eraseMemory(lbn: Int = 0, timeoutMs: Long = 125_000): Outcome {
            val l = ByteArray(4)
            for (i in 0..3) l[i] = (lbn ushr (8 * (3 - i))).toByte()
            return routineControl(0x01, 0xFF00, byteArrayOf(0x02, 0x44) + l + byteArrayOf(0x06), timeoutMs)
        }

        /** `31 01 0202 12 44 <lbn:4 BE> 0000` - checkMemory. The trailing `0000` is a fixed length constant,
         * **not** a CRC - nothing is computed for it. A failure here is non-fatal per the runbook (log and
         * continue); this can also emit `0x78` for a while. */
        fun checkMemory(lbn: Int = 0, timeoutMs: Long = 125_000): Outcome {
            val l = ByteArray(4)
            for (i in 0..3) l[i] = (lbn ushr (8 * (3 - i))).toByte()
            return routineControl(0x01, 0x0202, byteArrayOf(0x12, 0x44) + l + byteArrayOf(0x00, 0x00), timeoutMs)
        }

        fun checkProgrammingDependencies(): Outcome = routineControl(0x01, 0xFF01)

        /** Polls `22 F186` (ActiveDiagnosticSessionDataIdentifier) until the 4th data byte equals
         * [wantSession] (`0x02` = in the bootloader/programming session, `0x01` = back in the default
         * app session), or [maxWaitMs] elapses. Reconnects are the caller's job (see [FlashWrite]) -
         * this only polls whatever socket is current. */
        fun pollSession(wantSession: Int, maxWaitMs: Long, pollEveryMs: Long = 1000): Boolean {
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (System.currentTimeMillis() < deadline) {
                when (val o = readDataByIdentifier(0xF186, timeoutMs = pollEveryMs)) {
                    is Outcome.Positive -> if (o.data.size > 3 && (o.data[3].toInt() and 0xFF) == wantSession) return true
                    else -> {}
                }
            }
            return false
        }

        /** Polls `22 100A` (EnergyMode) until it reads [wantZero]`== 0` is read as *exactly zero* when
         * true, or *non-zero* when false, or [maxWaitMs] elapses. Returns the last value read (or null if
         * never answered) alongside whether the wanted condition was met. */
        fun pollEnergyMode(wantZero: Boolean, maxWaitMs: Long, pollEveryMs: Long = 1000): Pair<Boolean, Int?> {
            val deadline = System.currentTimeMillis() + maxWaitMs
            var last: Int? = null
            while (System.currentTimeMillis() < deadline) {
                when (val o = readDataByIdentifier(0x100A, timeoutMs = pollEveryMs)) {
                    is Outcome.Positive -> {
                        val v = if (o.data.size > 3) o.data[3].toInt() and 0xFF else null
                        last = v
                        if (v != null && (v == 0) == wantZero) return true to v
                    }
                    else -> {}
                }
            }
            return false to last
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
