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
            AppLog.e("HSFZ", "Clearing all car faults failed", e)
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
                return ResetResult(false, "The car's gateway ($zgwIp) does not answer.", unreachable = true)
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
}
