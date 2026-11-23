package com.nbtaad.editor.net

import com.nbtaad.editor.log.AppLog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class LocalNet(val name: String, val ip: String, val prefix: Int) {
    private val maskBits: Int
        get() = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))

    val broadcast: String
        get() = intToIp(ipToInt(ip) or maskBits.inv())

    /** Hosts to probe: the subnet, but never more than the /24 around the phone. */
    fun sweepHosts(): List<String> = lan(ip, maxOf(prefix, 24)).filter { it != ip }
}

fun ipToInt(ip: String): Int {
    val p = ip.split('.')
    return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
}

fun intToIp(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

/** All host addresses of the network containing [ip] with the given prefix length. */
fun lan(ip: String, prefix: Int): List<String> {
    val shift = 32 - prefix
    val mask = if (prefix <= 0) 0 else -1 shl shift
    val base = ipToInt(ip) and mask
    val count = 1 shl shift
    if (count <= 2) return emptyList()
    return (1 until count - 1).map { intToIp(base + it) }
}

enum class PortState { OPEN, REFUSED }

data class DetectResult(
    val zgwIp: String? = null,
    val vin: String? = null,
    val huIp: String? = null,
    val sshOpen: Boolean = false,
    val problem: String? = null,
    /**
     * The head unit was found as the router of the car's own Android Auto WiFi, where the car gateway is not reachable.
     * [problem] then says what is wrong with it (the login did not work), if anything.
     */
    val viaCarWifi: Boolean = false,
    /**
     * The car's own Android Auto WiFi is there and Android Auto answers on it, but SSH does not: the head unit's packet
     * filter (PF) blocks the phone. [problem] says what to do.
     */
    val pfBlocked: Boolean = false,
)

object NetworkBinder {
    /** The WiFi the app itself asked Android for (see [CarWifi]): when there is one, the app's traffic is tied to it. */
    @Volatile
    private var held: Network? = null

    /** The app was given a network of its own: tie the app to it. */
    fun hold(ctx: Context, network: Network) {
        held = network
        try {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(network)
        } catch (_: Exception) {
        }
    }

    /** That network is gone: forget it, and let go of the tie if it was the one the app was tied to. */
    fun drop(ctx: Context, network: Network) {
        if (held == network) dropAll(ctx)
    }

    fun dropAll(ctx: Context) {
        if (held == null) return
        held = null
        unbind(ctx)
    }

    /**
     * A WiFi dongle without internet makes Android route app traffic over mobile data.
     * Binding the process to the WiFi/Ethernet network avoids that. When the phone itself is the
     * hotspot there is no such network and nothing is bound (hotspot traffic is local anyway).
     *
     * The binding is looked at again (this is called) before every action with the car and before every internet
     * request: a network that is gone leaves a dead binding behind, and then no new connection can be made at all.
     */
    fun bindToLocalNetwork(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // the WiFi the app asked for itself (Connect again) comes first, as long as it is still there
            val mine = held
            if (mine != null) {
                if (cm.getNetworkCapabilities(mine) != null) {
                    cm.bindProcessToNetwork(mine)
                    return
                }
                held = null
            }
            @Suppress("DEPRECATION")
            val pick = cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            cm.bindProcessToNetwork(pick)
        } catch (_: Exception) {
        }
    }

    /** Lets go of any tie to a network: new connections are routed by the system again. */
    fun unbind(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }
}

object CarDetector {
    const val SSH_PORT = 22

    /** Head unit addresses seen on the ENET link-local network in the project's guides / issues. */
    private val KNOWN_HU = listOf("169.254.199.99", "169.254.199.119")

    private val SKIP_IF = listOf("lo", "rmnet", "ccmni", "dummy", "tun", "v4-", "clat", "ifb", "sit")

    fun localNetworks(): List<LocalNet> {
        val out = ArrayList<LocalNet>()
        try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return out)
            for (ni in all) {
                val name = ni.name.lowercase()
                if (!ni.isUp || ni.isLoopback) continue
                if (SKIP_IF.any { name.startsWith(it) }) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        out += LocalNet(ni.name, ip, ia.networkPrefixLength.toInt())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Broadcast the HSFZ ident request on every phone network and wait for the ZGW to answer. */
    fun discoverZgw(nets: List<LocalNet>): Hsfz.Ident? {
        repeat(2) {
            for (net in nets) {
                val targets = linkedSetOf(net.broadcast, "255.255.255.255")
                if (net.ip.startsWith("169.254.")) targets += "169.254.255.255"
                try {
                    DatagramSocket(InetSocketAddress(InetAddress.getByName(net.ip), 0)).use { sock ->
                        sock.broadcast = true
                        sock.soTimeout = 300
                        for (t in targets) {
                            runCatching {
                                sock.send(
                                    DatagramPacket(
                                        Hsfz.IDENT_REQUEST, Hsfz.IDENT_REQUEST.size,
                                        InetAddress.getByName(t), Hsfz.UDP_PORT
                                    )
                                )
                            }
                        }
                        val end = System.currentTimeMillis() + 1500
                        val buf = ByteArray(512)
                        while (System.currentTimeMillis() < end) {
                            val p = DatagramPacket(buf, buf.size)
                            try {
                                sock.receive(p)
                            } catch (e: SocketTimeoutException) {
                                continue
                            }
                            val ip = p.address.hostAddress ?: continue
                            if (ip == net.ip) continue
                            val ident = Hsfz.parseIdent(ip, p.data, p.length)
                            AppLog.d("Detect", "Answer to the broadcast from $ip on ${net.name}: " +
                                (if (ident != null) "an HSFZ identification (VIN ${ident.vin ?: "not in it"})" else "not HSFZ"))
                            if (ident != null) {
                                // only a gateway that gives a real VIN counts as a car
                                val vin = ident.vin ?: Hsfz.readVin(ip)
                                if (vin != null) return ident.copy(vin = vin)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    internal fun probe(host: String, port: Int, timeoutMs: Int): PortState? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        PortState.OPEN
    } catch (e: java.net.ConnectException) {
        if (e.message?.contains("refused", ignoreCase = true) == true) PortState.REFUSED else null
    } catch (_: Exception) {
        null
    }

    /** Parallel TCP probe. Returns only hosts that answered (OPEN) or actively refused (host is up). */
    fun sweep(hosts: Collection<String>, port: Int, timeoutMs: Int): Map<String, PortState> {
        val pool = Executors.newFixedThreadPool(64)
        try {
            val futures = hosts.map { h -> pool.submit(Callable { h to probe(h, port, timeoutMs) }) }
            val out = LinkedHashMap<String, PortState>()
            for (f in futures) {
                val (h, st) = f.get()
                if (st != null) out[h] = st
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    fun sshOpen(ip: String): Boolean = probe(ip, SSH_PORT, 2000) == PortState.OPEN

    internal fun banner(ip: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, SSH_PORT), 1500)
            s.soTimeout = 1500
            s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
        }
    } catch (_: Exception) {
        null
    }

    /** Lower is better. The head unit runs OpenSSH; a WiFi router/dongle usually runs dropbear. */
    private fun rank(ip: String): Int {
        if (ip in KNOWN_HU) return 0
        val b = banner(ip) ?: return 3
        return when {
            b.contains("dropbear", ignoreCase = true) -> 4
            b.contains("OpenSSH", ignoreCase = true) -> 1
            else -> 2
        }
    }

    /** Asked of a login: which of the Android Auto side's folders it sees as its own (not through /net/hu-omap). */
    const val LOCAL_FOLDERS_SCRIPT =
        "for d in /opt/aa /opt/hmi/ID5/data/ro/Plugins /var/opt/aa /var/opt/nbtapp; do if [ -d \$d ]; then echo \$d; fi; done"

    /** How many of those folders the login on [ip] has itself; -1 when the login did not work. */
    private fun localFolders(ip: String): Int = try {
        SshSession(ip).use { ssh ->
            val n = ssh.exec(LOCAL_FOLDERS_SCRIPT, timeoutMs = 8000).text.lines().count { it.trim().startsWith("/") }
            AppLog.d("Detect", "Login on $ip has $n of the Android Auto folders itself")
            n
        }
    } catch (e: Exception) {
        AppLog.d("Detect", "Looking at the folders of $ip failed: ${e.message}")
        -1
    }

    /** The address with the most folders (the first one on a tie); null for an empty list. */
    internal fun pickByLocalFolders(scores: List<Pair<String, Int>>): String? = scores.maxByOrNull { it.second }?.first

    /** Returns (head unit address or null, SSH port open). */
    fun findHeadUnit(nets: List<LocalNet>, zgw: String?): Pair<String?, Boolean> {
        val hosts = LinkedHashSet<String>()
        hosts += KNOWN_HU
        nets.forEach { hosts += it.sweepHosts() }
        if (nets.any { it.ip.startsWith("169.254.") }) hosts += lan("169.254.199.1", 24)
        if (zgw != null) hosts += lan(zgw, 24)
        hosts.removeAll(nets.map { it.ip }.toSet())

        val states = sweep(hosts, SSH_PORT, 700)
        var open = states.filterValues { it == PortState.OPEN }.keys.toList()
        AppLog.d(
            "Detect",
            "SSH sweep of ${hosts.size} addresses: open ${open.ifEmpty { listOf("none") }}, refused " +
                "${states.filterValues { it == PortState.REFUSED }.keys.toList().ifEmpty { listOf("none") }}"
        )
        if (open.size > 1 && zgw != null) open = open.filter { it != zgw }.ifEmpty { open }
        if (open.isNotEmpty()) {
            val ranked = open.map { it to rank(it) }
            val bestRank = ranked.minOf { it.second }
            val tied = ranked.filter { it.second == bestRank }.map { it.first }
            // two logins that look alike (the head unit has two processors that both answer): take the one that has the
            // Android Auto folders itself, because the other one sees only part of them (not /var, where NBTApps keeps its files)
            val best = if (tied.size > 1) pickByLocalFolders(tied.map { it to localFolders(it) }) ?: tied.first() else tied.first()
            AppLog.d("Detect", "Head unit candidates (address=rank, lower is better): $ranked; chosen $best")
            return best to true
        }
        val knownRefused = KNOWN_HU.firstOrNull { states[it] == PortState.REFUSED }
        AppLog.d("Detect", "No head unit with SSH open; known address that refused: $knownRefused")
        return knownRefused to false
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** What the SSH of the head unit calls itself: the OpenSSH of QNX. A router or a dongle says something else (dropbear, ...). */
    internal fun isHeadUnitBanner(banner: String?): Boolean = banner?.contains("QNX", ignoreCase = true) == true

    /**
     * The addresses that may be the head unit when the car gateway is not there: the routers, DHCP and DNS servers the
     * phone was given ([hints]) and the ".1" of each network, only on the phone's own networks and never the phone itself.
     */
    internal fun routerCandidates(nets: List<LocalNet>, hints: Collection<String>, max: Int = 4): List<String> {
        val phone = nets.map { it.ip }.toSet()
        val subnets = nets.map { CarNetLogic.subnetOf(it.ip) }
        val seen = LinkedHashSet<String>()
        for (h in hints + subnets.map { "$it.1" }) {
            if (h in phone || h == "0.0.0.0" || !IPV4.matches(h)) continue
            if (subnets.none { CarNetLogic.onSubnet(h, it) }) continue
            seen += h
        }
        return seen.take(max)
    }

    /**
     * While Android Auto runs, the phone sits on the car's own WiFi, where the head unit is the router and the car gateway
     * (ZGW) cannot be reached. A router of the phone's WiFi whose SSH is the head unit's (QNX) is taken as the head unit
     * there; the login is tried once, so a head unit that answers but does not let the app in is told apart.
     */
    internal fun findHeadUnitOnRouter(nets: List<LocalNet>, hints: Collection<String>): DetectResult? {
        for (c in routerCandidates(nets, hints)) {
            if (probe(c, SSH_PORT, 1500) != PortState.OPEN) continue
            val b = banner(c)
            if (!isHeadUnitBanner(b)) {
                AppLog.d("Detect", "$c answers on port 22 but is not the head unit (it says \"${b ?: "nothing"}\")")
                continue
            }
            AppLog.i("Detect", "No vehicle gateway, but $c is the router of this WiFi and its SSH says \"$b\": the head unit, over the vehicle's Android Auto WiFi")
            val folders = localFolders(c)
            return if (folders < 0) {
                DetectResult(
                    huIp = c, sshOpen = false, viaCarWifi = true,
                    problem = "The head unit answers on SSH over the vehicle's own WiFi, but the login did not work. Try again.",
                )
            } else {
                DetectResult(huIp = c, sshOpen = true, viaCarWifi = true)
            }
        }
        // no SSH anywhere: is it the packet filter? Android Auto's own port answers while port 22 does not
        val candidates = routerCandidates(nets, hints)
        if (candidates.isNotEmpty() && HeadUnitLight.judge(candidates.map { HeadUnitLight.see(it) }, true).kind == HeadUnitLight.Kind.PF_BLOCKED) {
            return DetectResult(pfBlocked = true, problem = HeadUnitLight.PF_PROBLEM)
        }
        return null
    }

    /**
     * The full detection: the vehicle's own gateway (ZGW) and, through it, the head unit. Only Prepare headunit needs
     * this (checking/fixing codings, and the ZGW-based reset both go through the gateway); everything else only needs
     * an SSH connection, which [detectSsh] looks for without this ZGW step. [hints]: the routers, DHCP and DNS servers
     * the phone was given (see [findHeadUnitOnRouter]).
     */
    fun detect(hints: Collection<String> = emptyList(), progress: (String) -> Unit): DetectResult {
        progress("Looking at the phone's networks…")
        val nets = localNetworks()
        if (nets.isEmpty()) {
            return DetectResult(
                problem = "The phone has no WiFi, hotspot or Ethernet network. " +
                    "Switch your hotspot on / join the vehicle's WiFi and try again."
            )
        }
        progress(
            "Networks: " + nets.joinToString { "${it.name} ${it.ip}/${it.prefix}" } +
                "\nLooking for the vehicle gateway (ZGW)…"
        )
        val ident = discoverZgw(nets)
        var zgw = ident?.ip
        var vin = ident?.vin
        if (zgw == null) {
            progress("No answer to the broadcast, scanning for port ${Hsfz.TCP_PORT}…")
            val hosts = nets.flatMap { it.sweepHosts() }.distinct()
            val candidates = sweep(hosts, Hsfz.TCP_PORT, 600).filterValues { it == PortState.OPEN }.keys.take(3)
            for (c in candidates) {
                progress("Asking $c for a VIN…")
                val v = Hsfz.readVin(c)
                if (v != null) {
                    zgw = c
                    vin = v
                    break
                }
            }
        }
        // no VIN, no car: a device that merely has the port open is not a gateway
        if (zgw == null || vin == null) {
            progress("No vehicle gateway here. Looking for the head unit on this WiFi…")
            findHeadUnitOnRouter(nets, hints)?.let { r ->
                if (r.pfBlocked) AppLog.i("Detect", "Result: Android Auto answers on the vehicle's own WiFi but SSH does not: the packet filter (PF) blocks it")
                else AppLog.i("Detect", "Result: head unit ${r.huIp} over the vehicle's own WiFi (no gateway), SSH open ${r.sshOpen}")
                return r
            }
            return DetectResult(
                problem = "No vehicle detected. Verify the connection (phone, dongle and vehicle on the same " +
                    "network) and try again."
            ).also { AppLog.i("Detect", "Result: no vehicle (gateway $zgw, VIN $vin)") }
        }
        progress("Gateway found at $zgw. Looking for the head unit…")
        val (hu, open) = findHeadUnit(nets, zgw)
        AppLog.i("Detect", "Result: gateway $zgw, VIN $vin, head unit $hu, SSH open $open")
        return DetectResult(zgwIp = zgw, vin = vin, huIp = hu, sshOpen = open)
    }

    /**
     * SSH only, no ZGW: a general sweep of the phone's local networks for the head unit's SSH (works over a direct
     * ENET connection, where the head unit is just some address on the swept subnet), falling back to
     * [findHeadUnitOnRouter] for the vehicle's own Android Auto WiFi (where the head unit is this WiFi's router) and
     * its packet-filter diagnosis. Used by everything except Prepare headunit, which needs the gateway too (codings,
     * and the gateway's own reset) and so uses [detect] instead.
     */
    fun detectSsh(hints: Collection<String> = emptyList(), progress: (String) -> Unit): DetectResult {
        progress("Looking at the phone's networks…")
        val nets = localNetworks()
        if (nets.isEmpty()) {
            return DetectResult(
                problem = "The phone has no WiFi, hotspot or Ethernet network. " +
                    "Switch your hotspot on / join the vehicle's WiFi and try again."
            )
        }
        progress("Networks: " + nets.joinToString { "${it.name} ${it.ip}/${it.prefix}" } + "\nLooking for the head unit's SSH…")
        val (hu, open) = findHeadUnit(nets, zgw = null)
        if (hu != null) {
            AppLog.i("Detect", "Result (SSH only): head unit $hu, SSH open $open")
            return DetectResult(huIp = hu, sshOpen = open)
        }
        progress("No head unit found on this network. Looking for it as the vehicle's own WiFi router…")
        findHeadUnitOnRouter(nets, hints)?.let { r ->
            if (r.pfBlocked) AppLog.i("Detect", "Result: Android Auto answers on the vehicle's own WiFi but SSH does not: the packet filter (PF) blocks it")
            else AppLog.i("Detect", "Result: head unit ${r.huIp} over the vehicle's own WiFi (no gateway), SSH open ${r.sshOpen}")
            return r
        }
        return DetectResult(
            problem = "No head unit found. Verify the connection (ENET cable, or the phone on the vehicle's own " +
                "WiFi) and try again."
        ).also { AppLog.i("Detect", "Result (SSH only): no head unit") }
    }
}
