package com.nbtaad.editor.net

import java.io.IOException

/**
 * The SSH connection could not even be made, so nothing was sent: the address may be old (the hotspot hands out new
 * ones when the dongle joins again), the head unit may be restarting, or the car asleep.
 */
class SshUnreachable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Wording for requests to the internet (GitHub). [friendlyError] is for the head unit ("Timed out talking to the head
 * unit") and must not be shown for these: an internet failure has nothing to do with the car.
 */
object Online {
    const val NO_INTERNET = "No internet connection."

    /** Whether the request failed because the phone has no (working) internet, as opposed to GitHub answering badly. */
    fun offline(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is java.net.UnknownHostException || t is java.net.ConnectException ||
                t is java.net.NoRouteToHostException || t is java.net.SocketTimeoutException ||
                t is java.net.SocketException || t is javax.net.ssl.SSLException
            ) return true
            val m = t.message ?: ""
            if (listOf("No network with internet", "Unable to resolve host", "Network is unreachable", "failed to connect")
                    .any { m.contains(it, ignoreCase = true) }
            ) return true
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * Whether the request failed because the app is still tied to a network that is gone ("socket failed: ENONET
     * (Machine is not on the network)"). The internet may be fine: the tie to the car's WiFi is what is dead.
     */
    fun deadNetwork(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            val m = t.message ?: ""
            if (m.contains("ENONET") || m.contains("Machine is not on the network", ignoreCase = true)) return true
            t = t.cause
            depth++
        }
        return false
    }

    fun describe(e: Throwable): String = when {
        offline(e) -> NO_INTERNET
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}

object Reach {
    /** Shown when an action needs the head unit, none was known and the app looked for it and found none. */
    const val NO_UNIT = "No head unit found. Make sure the phone has an SSH connection to it (ENET cable to the " +
        "vehicle, or the phone on the vehicle's own WiFi) and try again."

    /** Whether a failed connect means "no connection" (as opposed to a refused login or no common algorithm). */
    fun looksUnreachable(e: Throwable): Boolean {
        val msg = e.message ?: ""
        if (msg.contains("Auth fail", true) || msg.contains("Algorithm negotiation", true)) return false
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is java.net.SocketException || t is java.net.SocketTimeoutException || t is java.net.UnknownHostException) {
                return true
            }
            t = t.cause
            depth++
        }
        return listOf("timeout", "timed out", "refused", "No route", "unreachable", "End of IO Stream", "reset")
            .any { msg.contains(it, ignoreCase = true) }
    }
}

/** No head unit answered: an ordinary case (the phone is not near the car), not a fault, so it is not logged as an error. */
class NoUnitFound(message: String) : IOException(message)

/**
 * Runs [block] with the head unit's address. Where there is no address yet (none was found before, or the vehicle
 * changed) it is looked for first, and when no SSH connection can be made, it is looked for again: [rescan] looks
 * for its SSH and returns the address it found, or null. [onRescan] is called before each look. When it is looked
 * for and not found, the failure says why ([whyNot], else a general text). [block] runs a second time only after a
 * failed connect, never after a failure halfway through the work, and never right after a look that just found the
 * address.
 */
fun <T> runOnHeadUnit(
    host: () -> String?,
    rescan: () -> String?,
    onRescan: () -> Unit = {},
    whyNot: () -> String? = { null },
    block: (String) -> T,
): T {
    var looked = false
    val first = host() ?: run {
        onRescan()
        looked = true
        rescan()
    } ?: throw NoUnitFound(whyNot() ?: Reach.NO_UNIT)
    try {
        return block(first)
    } catch (e: SshUnreachable) {
        if (looked) throw e
        onRescan()
        val fresh = rescan() ?: throw e
        return block(fresh)
    }
}
