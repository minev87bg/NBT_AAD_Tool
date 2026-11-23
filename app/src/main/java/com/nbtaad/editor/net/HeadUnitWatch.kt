package com.nbtaad.editor.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * What the live look at the head unit's SSH says, for the pages that cannot do anything without it (the tile, the menu look).
 *
 * On the vehicle's own Android Auto WiFi the head unit is the router, and its packet filter (PF) can block the phone's SSH
 * while Android Auto itself keeps working: Android Auto's own port answers, port 22 does not. That is the one case the
 * light names (the person has to remove the PF in the head unit's Android Auto menu and restart Android Auto); the
 * other reds say only that nothing usable answers.
 */
object HeadUnitLight {
    enum class Kind { IDLE, CHECKING, GREEN, PF_BLOCKED, NOT_HEAD_UNIT, SILENT, NO_NETWORK }

    /** Android Auto's own port on the head unit: it answers while Android Auto runs, whatever the packet filter blocks. */
    const val AA_PORT = 5277

    /**
     * What one address answered: [ssh] on port 22 (null: nothing came back), what that SSH said first ([banner]) and
     * whether Android Auto's own port ([AA_PORT]) answers.
     */
    class Seen(val host: String, val ssh: PortState?, val banner: String?, val aaPort: Boolean)

    class Light(val kind: Kind, val host: String? = null, val said: String? = null) {
        val green: Boolean get() = kind == Kind.GREEN
    }

    /** The light for what the addresses answered. [haveNetwork] is false when the phone has no WiFi, hotspot or Ethernet at all. */
    fun judge(seen: List<Seen>, haveNetwork: Boolean): Light {
        if (!haveNetwork) return Light(Kind.NO_NETWORK)
        seen.firstOrNull { it.ssh == PortState.OPEN && CarDetector.isHeadUnitBanner(it.banner) }
            ?.let { return Light(Kind.GREEN, it.host) }
        seen.firstOrNull { it.ssh != PortState.OPEN && it.aaPort }
            ?.let { return Light(Kind.PF_BLOCKED, it.host) }
        seen.firstOrNull { it.ssh == PortState.OPEN }
            ?.let { return Light(Kind.NOT_HEAD_UNIT, it.host, it.banner) }
        return Light(Kind.SILENT)
    }

    const val PF_TITLE = "Remove the PF on the head unit first"

    /** The steps of the one red that has a cure the person does on the head unit. */
    val PF_STEPS = listOf(
        "On the head unit, open the Android Auto menu and remove the PF (packet filter).",
        "Restart Android Auto, so that the change takes effect.",
        "Wait here. This page keeps looking and turns green by itself.",
    )

    /** The same, in one text, for the places that show a single line of trouble (a failed look at the head unit). */
    const val PF_PROBLEM = "The head unit's packet filter (PF) blocks SSH over the vehicle's own WiFi, so the app cannot reach it. " +
        "Remove the PF in the head unit's Android Auto menu, then restart Android Auto so that the change takes effect, " +
        "and try again."

    /** What [host] answers on SSH and on Android Auto's own port: short connections that are closed again. */
    fun see(host: String): Seen {
        val ssh = CarDetector.probe(host, CarDetector.SSH_PORT, 1500)
        val banner = if (ssh == PortState.OPEN) CarDetector.banner(host) else null
        val aa = ssh != PortState.OPEN && CarDetector.probe(host, AA_PORT, 800) == PortState.OPEN
        return Seen(host, ssh, banner, aa)
    }

    const val PF_WHY = "The packet filter blocks SSH (port 22) over the vehicle's own WiFi, so the app cannot reach the head unit. " +
        "Android Auto itself still answers."

    fun headline(l: Light): String = when (l.kind) {
        Kind.IDLE -> "Not scanned yet."
        Kind.CHECKING -> "Looking at the connection to the head unit…"
        Kind.GREEN -> "Green light: SSH answers on ${l.host}. You can go on."
        Kind.PF_BLOCKED -> PF_TITLE
        Kind.NOT_HEAD_UNIT -> "Something answers on port 22 of ${l.host}, but it is not the head unit's SSH" +
            (l.said?.let { " (it says \"$it\")" } ?: "") + "."
        Kind.SILENT -> "The head unit's SSH does not answer."
        Kind.NO_NETWORK -> "The phone has no WiFi network."
    }

    /** What to do for the reds that have no steps of their own. */
    fun advice(l: Light): String? = when (l.kind) {
        Kind.SILENT -> "Make sure the phone has an SSH connection to the head unit: Android Auto running on the " +
            "vehicle's own WiFi, or connected another way (ENET, dongle). Then scan again."
        Kind.NO_NETWORK -> "Connect the phone to a network that reaches the head unit (the vehicle's own WiFi, or a dongle), then scan again."
        Kind.NOT_HEAD_UNIT -> "Check that the phone's SSH connection reaches the head unit, not another device, then scan again."
        else -> null
    }
}

/**
 * The look at the head unit's SSH, done once whenever the person presses SCAN THE HEAD UNIT (not on its own): asks the
 * addresses that may be the head unit (the one the app knows and the routers the phone was given) whether SSH answers,
 * and keeps the [light]. It only opens short connections and closes them again.
 */
class HeadUnitWatch(private val ctx: Context, private val known: () -> String?) {
    var light by mutableStateOf(HeadUnitLight.Light(HeadUnitLight.Kind.IDLE)); private set

    /** When the last look ended (wall clock) and how many there were since the page opened. */
    var lastTry by mutableStateOf(0L); private set
    var tries by mutableStateOf(0); private set

    val green: Boolean get() = light.green

    /** One look, started by the person. True when this look turned the light green and it was not green before. */
    suspend fun look(): Boolean = withContext(Dispatchers.IO) {
        val was = light.kind
        light = HeadUnitLight.Light(HeadUnitLight.Kind.CHECKING)
        val now = try {
            NetworkBinder.bindToLocalNetwork(ctx)
            val nets = CarDetector.localNetworks()
            val hints = try {
                PortProbe.netFacts(ctx).let { it.gateways + it.dhcp + it.dns }
            } catch (_: Exception) {
                emptyList()
            }
            val hosts = LinkedHashSet<String>()
            known()?.let { hosts += it }
            hosts += CarDetector.routerCandidates(nets, hints)
            val seen = coroutineScope { hosts.map { h -> async { HeadUnitLight.see(h) } }.awaitAll() }
            HeadUnitLight.judge(seen, nets.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("SshWatch", "The look failed: ${e.message ?: e.javaClass.simpleName}")
            HeadUnitLight.Light(HeadUnitLight.Kind.SILENT)
        }
        light = now
        lastTry = System.currentTimeMillis()
        tries++
        if (now.kind != was) {
            AppLog.i("SshWatch", "${was.name} -> ${now.kind.name}${now.host?.let { " ($it)" } ?: ""}: ${HeadUnitLight.headline(now)}")
        }
        now.green && was != HeadUnitLight.Kind.GREEN
    }
}
