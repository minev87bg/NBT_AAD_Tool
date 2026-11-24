package com.nbtaad.editor.net

/**
 * A read-only look at the head unit's own networking, run over the same SSH connection already used to reach it -
 * nothing here writes anything, starts anything, or changes any setting.
 *
 * Why: over a direct ENET/WiFi-ENET-adapter connection, the phone sees both the vehicle gateway (ZGW) and the head
 * unit itself as two separate hosts on the same network (e.g. ZGW 192.168.0.8, head unit 192.168.0.10 - see the
 * app's own logs). Over the car's own Android Auto WiFi, the phone only ever sees the head unit (as the router of
 * that WiFi, e.g. 172.16.224.1) - the gateway is never found there. Those two facts together raise the question of
 * whether the head unit is sitting on both networks itself - its own Android Auto WiFi on one side, the vehicle's
 * ZGW network on the other - which would make it possible, at least in principle, for the head unit to pass traffic
 * between the two (so that the app's existing ZGW-based tools - the official UDS "ECU reset", coding checks - could
 * be reached from the Android Auto WiFi alone, without an ENET cable or adapter). This only reads what is already
 * there to answer that question: the head unit's own interfaces and addresses, its routing table, whether it is
 * currently set to pass traffic between interfaces at all (it almost certainly is not, by default), and any
 * firewall/NAT rules already in place. No change follows from running this by itself.
 *
 * The head unit's shell is QNX (not Linux) - its TCP/IP stack (io-pkt) is BSD-derived, so the BSD-style tools it is
 * actually expected to have (`ifconfig`, `netstat`, `sysctl`) are tried first; their Linux equivalents are tried too,
 * in case a compatibility layer exists. Nothing here depends on guessing right: each command is on its own line, so
 * one that does not exist on this shell just prints its own "not found" (`2>&1` keeps that in the report rather than
 * discarding it) and the next line still runs. The report is read by a person, not parsed by this app - a gap in it
 * is something to look at, not a bug.
 */
object NetDiag {
    private val script = """
        echo "== ifconfig -a =="
        ifconfig -a 2>&1
        echo
        echo "== netstat -rn (routing table) =="
        netstat -rn 2>&1
        echo
        echo "== ip addr (only if this shell has it) =="
        ip addr 2>&1
        echo
        echo "== ip route (only if this shell has it) =="
        ip route 2>&1
        echo
        echo "== IP forwarding (BSD/QNX name, then the Linux one) =="
        sysctl net.inet.ip.forwarding 2>&1
        cat /proc/sys/net/ipv4/ip_forward 2>&1
        echo
        echo "== firewall / NAT rules, if any exist here =="
        iptables -L -n -v 2>&1
        iptables -t nat -L -n -v 2>&1
        echo
        echo "== processes (QNX pidin, then plain ps) =="
        pidin 2>&1
        ps 2>&1
        echo
        echo "== head unit software =="
        head -1 /opt/sys/etc/nbt_version.txt 2>/dev/null
    """.trimIndent()

    /**
     * Runs the read-only script above on [host] and returns its raw output, headed by which address it actually
     * asked. Throws (does not catch) if the SSH connection itself fails - same as every other direct SSH call in
     * this package - so the caller's own error handling applies.
     */
    fun run(host: String): String {
        val out = SshSession(host).use { ssh -> ssh.exec(script, timeoutMs = 20_000).text }
        return "Read over SSH from $host.\n\n" + out.trimEnd() + "\n"
    }
}
