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
 * currently set to pass traffic between interfaces at all, and any firewall/NAT rules already in place. No change
 * follows from running this by itself.
 *
 * A first real run already answered part of this: the head unit's own IP forwarding is on system-wide
 * (`net.inet.ip.forwarding = 1`), and its wired interface facing the vehicle (`dwc0`) carries a `/16` alias
 * (`192.168.0.10`) that already covers the ZGW's own address (`192.168.0.8`) with no extra hop needed on the head
 * unit's side. What is still unknown is whether its firewall (`pf` - see below) is the thing actually standing
 * between the Android Auto WiFi interface and that network, which is why reading `pf`'s own rules matters as much as
 * the interfaces and routes do.
 *
 * The head unit's shell is QNX (not Linux) - its TCP/IP stack (io-pkt) is BSD-derived, so the BSD-style tools it is
 * actually expected to have (`ifconfig`, `netstat`, `sysctl`, `pfctl`) are tried first; their Linux equivalents are
 * tried too, in case a compatibility layer exists. A first real run against the head unit confirmed the BSD side:
 * `pflog0` shows up as one of its own interfaces, meaning the firewall here is BSD `pf`, not Linux netfilter - so
 * `pfctl`'s own rule/NAT listing is what actually answers whether traffic is allowed between interfaces, not
 * `iptables` (which this shell does not have at all). Process names alone do not say which interface a DHCP/DNS
 * helper (`dhcpd`, `DnsMasq`, `dhcp.client` have all turned up here) actually serves, so their full command lines are
 * read too, not just their names. Nothing here depends on guessing right: each command is on its own line, so one
 * that does not exist on this shell just prints its own "not found" (`2>&1` keeps that in the report rather than
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
        echo "== pf rules (BSD packet filter - this shell's real firewall) =="
        pfctl -s rules 2>&1
        echo
        echo "== pf NAT/redirection rules =="
        pfctl -s nat 2>&1
        echo
        echo "== pf state/info =="
        pfctl -s info 2>&1
        echo
        echo "== firewall / NAT rules, the Linux way (only if this shell has it) =="
        iptables -L -n -v 2>&1
        iptables -t nat -L -n -v 2>&1
        echo
        echo "== processes (QNX pidin, name only) =="
        pidin 2>&1
        ps 2>&1
        echo
        echo "== processes with their full command line (QNX pidin -F) =="
        pidin -F "%p %n %a %A" 2>&1
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
