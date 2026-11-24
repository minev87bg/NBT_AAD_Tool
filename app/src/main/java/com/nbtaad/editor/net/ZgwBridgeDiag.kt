package com.nbtaad.editor.net

/**
 * Read-only groundwork for the ZGW-over-Android-Auto-WiFi idea (see NetDiag's own doc comment for the full
 * background). NetDiag already showed the shape of the problem: `dhcpd` on `uap0` is the thing that could, in
 * principle, hand a connecting phone an extra route to the vehicle's own 192.168.0.0/16 network (where ZGW lives),
 * via the DHCP "classless static routes" option (RFC 3442, aka option 121) - a normal, long-standing DHCP option
 * that Android already understands when a server offers it. But NetDiag only told us the config file's *path*
 * (`/var/opt/conn/dhcpd.uap0.conf`), not its actual contents or whether this particular build of `dhcpd` even
 * recognizes that option by that name. Writing the real one-line edit without knowing those two things would be
 * guessing at syntax on a live vehicle - this exists so the next edit is copied from fact, not assumed.
 *
 * Three things, all read-only:
 * 1. The actual current file content, so the new line can be placed inside the right `subnet { }` block instead of
 *    just appended blindly to the end (which some dhcpd builds would reject as being outside any subnet scope).
 * 2. The active lease file, so the configured lease time is known (that's how long an already-connected phone would
 *    keep routing the old way before naturally asking again - relevant to whether a manual reconnect is needed).
 * 3. A dry-run syntax check of the *candidate* line - but tested against a throwaway copy in /tmp, never the real
 *    file, and deleted again immediately after. This answers "does this dhcpd build even accept this option
 *    spelled this way" before anything real is touched, without touching anything real to find out.
 *
 * Nothing here starts, stops, or restarts any daemon, and nothing here writes to any file under /var/opt/conn
 * itself - only to a scratch copy under /tmp that it deletes in the same command.
 */
object ZgwBridgeDiag {
    private val script = """
        echo "== the real config file, as it stands today =="
        cat /var/opt/conn/dhcpd.uap0.conf 2>&1
        echo
        echo "== its age/size (how long since it was last written) =="
        ls -la /var/opt/conn/dhcpd.uap0.conf 2>&1
        echo
        echo "== active leases on uap0 right now (and the lease time they were granted for) =="
        cat /var/opt/conn/dhcpd.uap0.leases 2>&1
        echo
        echo "== dhcpd's own version/build info =="
        dhcpd --version 2>&1
        dhcpd -V 2>&1
        echo
        echo "== sanity check: this build can parse its OWN current config (baseline, changes nothing) =="
        dhcpd -t -cf /var/opt/conn/dhcpd.uap0.conf 2>&1
        echo
        echo "== dry run only: does this build accept the classless-static-route option, spelled this way? =="
        echo "   (writes a throwaway copy under /tmp, never the real file; deletes it again right after)"
        cp /var/opt/conn/dhcpd.uap0.conf /tmp/dhcpd.uap0.conf.zgwtest 2>&1
        echo 'option rfc3442-classless-static-routes 16, 192, 168, 172, 16, 224, 1;' >> /tmp/dhcpd.uap0.conf.zgwtest 2>&1
        dhcpd -t -cf /tmp/dhcpd.uap0.conf.zgwtest 2>&1
        rm -f /tmp/dhcpd.uap0.conf.zgwtest 2>&1
        echo "(scratch file removed)"
    """.trimIndent()

    /**
     * Runs the read-only script above on [host]. Throws (does not catch) if the SSH connection itself fails -
     * same convention as NetDiag.run and every other direct SSH call in this package.
     */
    fun run(host: String): String {
        val out = SshSession(host).use { ssh -> ssh.exec(script, timeoutMs = 20_000).text }
        return "Read over SSH from $host.\n\n" + out.trimEnd() + "\n"
    }
}
