package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reaches the internet even while the app is bound to the car's WiFi / Ethernet (which has none).
 *
 * The request goes out over a network that really has internet (mobile data, a WiFi repeater with internet,
 * Ethernet with internet), picked by looking at the network's capabilities. If nothing is marked as validated, the
 * phone's default network is tried once, it may still work.
 */
/** The server answered, and what it said is the answer (a refused key, a used-up limit): no other network is tried. */
open class Answered(message: String) : IOException(message)

object Internet {
    /**
     * Runs [block] with a way to open connections; the next way is tried when it throws.
     *
     * While the app works with the car it is tied to the car's WiFi (see [NetworkBinder]). When that WiFi is gone (the
     * hotspot was stopped, the phone moved on) the tie is dead and every new connection fails with ENONET ("Machine is
     * not on the network"), the internet included, although the phone has full speed. So the tie is looked at again
     * before every request, and when a request still dies of it, the tie is let go of and the phone's own routing is used.
     */
    fun <T> run(ctx: Context, block: (open: (URL) -> HttpURLConnection) -> T): T {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        NetworkBinder.bindToLocalNetwork(ctx)
        val nets = internetNetworks(cm)
        var last: Exception? = null
        var dead = false
        for (n in nets) {
            try {
                return block { u -> n.openConnection(u) as HttpURLConnection }
            } catch (e: Answered) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Net", "Request failed on network $n: ${e.javaClass.simpleName}: ${e.message}")
                last = e
                if (Online.deadNetwork(e)) dead = true
            }
        }
        if (nets.isEmpty() || dead) {
            // nothing is marked as having internet (it may just have connected), or what is marked did not work: try the
            // phone's own default network, asked for by name
            val def = try { cm.activeNetwork } catch (_: Exception) { null }
            if (def != null && def !in nets) {
                try {
                    return block { u -> def.openConnection(u) as HttpURLConnection }
                } catch (e: Answered) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Net", "Request failed on the default network: ${e.javaClass.simpleName}: ${e.message}")
                    last = e
                    if (Online.deadNetwork(e)) dead = true
                }
            }
            // last resort: no tie to any network at all, the system routes the request as it does for any app
            if (dead || def == null) {
                NetworkBinder.unbind(ctx)
                try {
                    return block { u -> u.openConnection() as HttpURLConnection }
                } catch (e: Answered) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Net", "Request failed on the system's own routing: ${e.javaClass.simpleName}: ${e.message}")
                    last = e
                }
            }
        }
        throw last ?: IOException("No network with internet")
    }

    /**
     * A quick, local check for whether the phone looks like it has a network with internet right now: no request is
     * sent anywhere. For a periodic background check (an app update, say), this is what decides whether to even try,
     * so a phone that is plainly offline does not show "Checking…" every time only to quietly fail a moment later.
     * A network that looks fine can still not truly have internet (a captive portal, say): [run] is what actually
     * finds that out, by trying the request and falling back the same way it always does.
     */
    fun hasConnectivity(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (internetNetworks(cm).isNotEmpty()) return true
        val def = try { cm.activeNetwork } catch (e: Exception) { null } ?: return false
        val caps = try { cm.getNetworkCapabilities(def) } catch (e: Exception) { null } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun internetNetworks(cm: ConnectivityManager): List<Network> {
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.toList()
        return all.filter { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
