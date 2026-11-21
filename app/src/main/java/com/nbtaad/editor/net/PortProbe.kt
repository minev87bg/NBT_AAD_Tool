package com.nbtaad.editor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.nbtaad.editor.log.AppLog
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** How a TCP port answered a connection attempt. */
enum class Answer { OPEN, REFUSED, SILENT, OTHER }

/** What the scan of one address found. [stopped]: the person stopped it before every port was tried. */
data class HostScan(
    val host: String,
    val open: List<Int>,
    val refused: Int,
    val silent: Int,
    val other: Int,
    val firstOther: String?,
    val tried: Int,
    val total: Int,
    val seconds: Int,
    val stopped: Boolean,
)

/** What the phone says about its networks: the addresses of routers, DNS servers and DHCP servers, and one line per network. */
data class NetFacts(val gateways: List<String>, val dns: List<String>, val dhcp: List<String>, val lines: List<String>)

/**
 * Looks, and only looks: which TCP ports of the head unit answer on the phone's WiFi while Android Auto runs (the car
 * pulls the phone onto its own WiFi then, and SSH on port 22 is not there). It opens short connections and closes them
 * again; nothing is sent, nothing is changed on the car.
 */
object PortProbe {
    /** Tried first on every address of the network: SSH, telnet, DNS, web, the daemon's phone port and the usual spare ones. */
    val QUICK_PORTS = listOf(22, 23, 53, 80, 443, 2222, 5277, 5555, 8022, 8080)

    /** The port the daemon tells the phone to use (NBT_AA_PORT): it is not connected to a second time for a banner. */
    const val AA_PORT = 5277

    const val TIMEOUT_MS = 300
    const val THREADS = 256
    const val MAX_DEEP_HOSTS = 3

    /** QUICK: the low ports where services live. FULL: every port. */
    fun portsFor(full: Boolean): IntArray =
        if (full) IntArray(65535) { it + 1 }
        else (1..10000).toList().plus(QUICK_PORTS).plus(52790).distinct().sorted().toIntArray()

    internal fun tcp(host: String, port: Int, timeoutMs: Int, note: (String) -> Unit = {}): Answer = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        Answer.OPEN
    } catch (_: SocketTimeoutException) {
        Answer.SILENT
    } catch (e: ConnectException) {
        val m = e.message ?: ""
        when {
            m.contains("refused", ignoreCase = true) -> Answer.REFUSED
            m.contains("timed out", ignoreCase = true) || m.contains("ETIMEDOUT") -> Answer.SILENT
            else -> {
                note(m)
                Answer.OTHER
            }
        }
    } catch (e: Exception) {
        note(e.javaClass.simpleName + ": " + (e.message ?: ""))
        Answer.OTHER
    }

    /**
     * Runs [work] for the numbers 0 until [total] on [threads] threads. [progress] is called now and then (and at the end)
     * with how many are done. Returns false when [cancelled] stopped it before all were done.
     */
    internal fun runWorkers(total: Int, threads: Int, cancelled: () -> Boolean, progress: (Int) -> Unit, work: (Int) -> Unit): Boolean {
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val workers = List(minOf(threads, total).coerceAtLeast(1)) {
            Thread {
                while (!cancelled()) {
                    val i = next.getAndIncrement()
                    if (i >= total) break
                    work(i)
                    done.incrementAndGet()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        while (workers.any { it.isAlive }) {
            workers.firstOrNull { it.isAlive }?.join(400)
            progress(done.get())
        }
        progress(done.get())
        return done.get() >= total
    }

    /** Tries every port of [ports] on [host]. */
    fun scanHost(
        host: String,
        ports: IntArray,
        timeoutMs: Int = TIMEOUT_MS,
        threads: Int = THREADS,
        cancelled: () -> Boolean = { false },
        progress: (tried: Int, open: List<Int>) -> Unit = { _, _ -> },
    ): HostScan {
        val open = ConcurrentLinkedQueue<Int>()
        val refused = AtomicInteger()
        val silent = AtomicInteger()
        val other = AtomicInteger()
        val firstOther = AtomicReference<String?>(null)
        val started = System.currentTimeMillis()
        val finished = runWorkers(ports.size, threads, cancelled, { progress(it, open.sorted()) }) { i ->
            when (tcp(host, ports[i], timeoutMs) { firstOther.compareAndSet(null, it) }) {
                Answer.OPEN -> open.add(ports[i])
                Answer.REFUSED -> refused.incrementAndGet()
                Answer.SILENT -> silent.incrementAndGet()
                Answer.OTHER -> other.incrementAndGet()
            }
        }
        val tried = open.size + refused.get() + silent.get() + other.get()
        return HostScan(
            host, open.sorted(), refused.get(), silent.get(), other.get(), firstOther.get(),
            tried, ports.size, ((System.currentTimeMillis() - started) / 1000).toInt(), !finished,
        )
    }

    /** The first line a service sends when a connection is made (SSH does), made printable; null when it says nothing. */
    internal fun banner(host: String, port: Int): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 1200)
            s.soTimeout = 700
            val buf = ByteArray(120)
            val n = s.getInputStream().read(buf)
            if (n <= 0) null else printable(buf, n)
        }
    } catch (_: Exception) {
        null
    }

    internal fun printable(buf: ByteArray, n: Int): String =
        String(buf, 0, n, Charsets.ISO_8859_1).lineSequence().first()
            .map { if (it.code in 32..126) it else '.' }.joinToString("").trim().take(80)

    /** The meaning of the counts, in a sentence. */
    internal fun reading(s: HostScan): String = when {
        s.tried == 0 -> "nothing was tried"
        s.refused == 0 && s.silent == 0 && s.other == 0 -> "every port tried is open"
        s.refused == 0 && s.open.isEmpty() && s.other == 0 ->
            "nothing answers at all: a firewall drops everything here, or nothing is at this address"
        s.refused == 0 && s.other == 0 -> "the ports that are not open are dropped without an answer (a firewall)"
        s.silent == 0 && s.other == 0 -> "closed ports answer \"refused\": nothing drops packets here, the open ports are all there is"
        s.other > s.refused + s.silent -> "most attempts failed on the phone's side (${s.firstOther ?: "no reason given"})"
        else -> "some ports are refused and some are dropped, so a firewall treats ports differently"
    }

    internal fun describe(s: HostScan): String {
        val ports = if (s.open.isEmpty()) "none" else s.open.joinToString(", ")
        return buildString {
            append("${s.host}: open ports: $ports\n")
            append("  refused ${s.refused}, silent ${s.silent}, failed ${s.other}; tried ${s.tried} of ${s.total} ports in ${s.seconds} s")
            if (s.stopped) append(" (stopped)")
            append("\n  ${reading(s)}")
        }
    }

    /** "1.0.168.192" as DhcpInfo keeps it (the first part in the lowest byte) -> "192.168.0.1"; null for 0. */
    internal fun leToIp(v: Int): String? =
        if (v == 0) null else "${v and 255}.${(v shr 8) and 255}.${(v shr 16) and 255}.${(v shr 24) and 255}"

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * The addresses worth a full scan, in this order: the routers, the DHCP servers, the DNS servers, the addresses
     * that answered the quick look, the ".1" of every network. Only addresses on one of the phone's networks, never
     * the phone itself.
     */
    internal fun candidateHosts(
        phoneIps: Collection<String>,
        subnets: Collection<String>,
        groups: List<List<String>>,
        max: Int = MAX_DEEP_HOSTS,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (group in groups) for (h in group) {
            if (h in phoneIps || h == "0.0.0.0" || !IPV4.matches(h)) continue
            if (subnets.none { CarNetLogic.onSubnet(h, it) }) continue
            seen += h
        }
        return seen.take(max)
    }

    private fun transportName(c: NetworkCapabilities): String {
        val names = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_CELLULAR to "mobile",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
        )
        return names.filter { c.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
    }

    /** What Android knows about the phone's networks (not the mobile one). Never throws. */
    @Suppress("DEPRECATION")
    fun netFacts(ctx: Context): NetFacts {
        val gateways = LinkedHashSet<String>()
        val dns = LinkedHashSet<String>()
        val dhcp = LinkedHashSet<String>()
        val lines = ArrayList<String>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                val gws = lp.routes.mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }.filter { it != "0.0.0.0" }.distinct()
                val servers = lp.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                gateways += gws
                dns += servers
                val internet = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "internet" else "no internet"
                val addrs = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                    .joinToString { "${it.hostAddress}" }
                lines += "${lp.interfaceName} (${transportName(c)}, $internet): address $addrs, routers $gws, DNS $servers"
            }
        } catch (e: Exception) {
            lines += "The network details could not be read: ${e.message}"
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val di = wm.dhcpInfo
            if (di != null) {
                leToIp(di.serverAddress)?.let { dhcp += it }
                leToIp(di.gateway)?.let { gateways += it }
                lines += "DHCP info: server ${leToIp(di.serverAddress)}, gateway ${leToIp(di.gateway)}, " +
                    "phone ${leToIp(di.ipAddress)}"
            }
        } catch (_: Exception) {
        }
        return NetFacts(gateways.toList(), dns.toList(), dhcp.toList(), lines)
    }

    /**
     * The whole probe: a quick look at every address of the phone's networks, then every port of [deepPorts] on the
     * few addresses that matter. [say] gets the progress and, at the end, nothing more: the result is the return value
     * (and the details are in the app log, tag "Probe").
     */
    internal fun runWith(
        nets: List<LocalNet>,
        facts: NetFacts,
        deepPorts: IntArray,
        say: (String) -> Unit,
        cancelled: () -> Boolean,
    ): String {
        if (nets.isEmpty()) {
            return "The phone has no WiFi network to look at. Join the car's WiFi (or let Android Auto do it) and try again."
        }
        AppLog.i("Probe", "Phone networks: " + nets.joinToString { "${it.name} ${it.ip}/${it.prefix}" })
        facts.lines.forEach { AppLog.i("Probe", it) }

        // 1. who is there: every address of every network, a handful of ports each
        val hosts = nets.flatMap { it.sweepHosts() }.distinct()
        val targets = hosts.flatMap { h -> QUICK_PORTS.map { h to it } }
        val answers = ConcurrentLinkedQueue<Triple<String, Int, Answer>>()
        say("Looking at ${hosts.size} addresses…")
        runWorkers(targets.size, THREADS, cancelled, { say("Looking at ${hosts.size} addresses… ${it * 100 / targets.size.coerceAtLeast(1)} %") }) { i ->
            val (h, p) = targets[i]
            val a = tcp(h, p, 400)
            if (a == Answer.OPEN || a == Answer.REFUSED) answers.add(Triple(h, p, a))
        }
        val byHost = answers.groupBy { it.first }
        for ((h, list) in byHost.entries.sortedBy { ipToInt(it.key) }) {
            val open = list.filter { it.third == Answer.OPEN }.map { it.second }.sorted()
            val refused = list.filter { it.third == Answer.REFUSED }.map { it.second }.sorted()
            AppLog.i("Probe", "Quick look: $h answers; open $open, refused $refused")
        }
        if (byHost.isEmpty()) AppLog.i("Probe", "Quick look: no address answered on $QUICK_PORTS")

        // 2. which of them get every port tried
        val aliveByLoudness = byHost.entries.sortedWith(compareBy({ -it.value.size }, { ipToInt(it.key) })).map { it.key }
        val subnets = nets.map { CarNetLogic.subnetOf(it.ip) }.distinct()
        val candidates = candidateHosts(
            phoneIps = nets.map { it.ip },
            subnets = subnets,
            groups = listOf(facts.gateways, facts.dhcp, facts.dns, aliveByLoudness, subnets.map { "$it.1" }),
        )
        AppLog.i("Probe", "Addresses for the port scan: $candidates")
        if (candidates.isEmpty()) {
            return "No address on the phone's WiFi answered, and Android names no router. " +
                "The details are in the app log (Probe)."
        }

        // 3. every port on each of them
        val reports = ArrayList<String>()
        for ((n, host) in candidates.withIndex()) {
            if (cancelled()) break
            val label = "$host (${n + 1} of ${candidates.size})"
            say("Trying ${deepPorts.size} ports of $label…")
            val scan = scanHost(host, deepPorts, cancelled = cancelled) { tried, open ->
                say("Trying ${deepPorts.size} ports of $label… ${tried * 100 / deepPorts.size.coerceAtLeast(1)} %" +
                    (if (open.isEmpty()) "" else "\nOpen so far: ${open.joinToString(", ")}"))
            }
            AppLog.i("Probe", describe(scan).replace("\n  ", " | "))
            for (p in scan.open.take(12)) {
                val b = if (p == AA_PORT) null else banner(host, p)
                AppLog.i("Probe", "$host:$p " + (if (p == AA_PORT) "(not asked again: Android Auto's own port)" else b?.let { "says \"$it\"" } ?: "says nothing"))
            }
            reports += describe(scan)
        }
        return reports.joinToString("\n\n") + (if (cancelled()) "\n\nStopped." else "") + "\n\nThe details are in the app log (Probe)."
    }

    fun run(ctx: Context, nets: List<LocalNet>, full: Boolean, say: (String) -> Unit, cancelled: () -> Boolean): String =
        runWith(nets, netFacts(ctx), portsFor(full), say, cancelled)
}
