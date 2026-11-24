package com.nbtaad.editor.net

/**
 * Read-only groundwork for the ZGW-over-Android-Auto-WiFi idea (see NetDiag's own doc comment for the full
 * background). NetDiag already showed the shape of the problem: `dhcpd` on `uap0` is the thing that could, in
 * principle, hand a connecting phone an extra route to the vehicle's own 192.168.0.0/16 network (where ZGW lives),
 * via the DHCP "classless static routes" option (RFC 3442, aka option 121) - a normal, long-standing DHCP option
 * that Android already understands when a server offers it.
 *
 * A first real run already answered most of what this needs to know:
 * - The file is small and has exactly one `subnet 172.16.224.0 ... { }` block - no ambiguity about where a new
 *   line would go. It is headed "File generated automatically - Do not edit", and its own timestamp sat only
 *   minutes behind an active lease being renewed - strong evidence something regenerates it around when the AA
 *   WiFi service (re)starts, not just once at the factory. That matters for how long any edit here would actually
 *   last, independent of anything this app does to revert it.
 * - The naive one-line edit FAILS on this build: `isc-dhcpd-4.1-ESV-R3` does not predefine the symbolic name
 *   `rfc3442-classless-static-routes` (`unknown option dhcp.rfc3442-classless-static-routes`). That option support
 *   was added to later ISC dhcpd releases; this vendor-frozen 2011-era ESV branch predates it. The fix is a second,
 *   equally standard line - ISC dhcpd has always let a config declare its own option codes:
 *       option rfc3442-classless-static-routes code 121 = array of integer 8;
 *   placed once near the top of the file, before the existing line actually USING that name inside the subnet
 *   block. This isn't a workaround specific to this car - it's the documented, portable way to use option 121 on
 *   any ISC dhcpd, old or new.
 *
 * What's tested here, all still read-only:
 * 1. The real file content and its age (unchanged from before - kept so a fresh run is self-contained).
 * 2. The active lease file (lease time, so it's known how long an already-connected phone would keep routing the
 *    old way before naturally asking again).
 * 3. A dry-run syntax check of the CORRECTED two-line form (declare, then use) - built by reading the real file
 *    line-by-line and inserting the two new lines after their real anchor lines (not a hardcoded guess at the
 *    file's shape), tested against a throwaway copy in /tmp, never the real file, deleted again immediately after.
 *    Idempotent: a real run showed that once the route is already live in the real file, re-running this naively
 *    would insert a SECOND copy of both lines right after the same anchors (which are still there - the anchors
 *    were never removed, only added after). Fixed by checking first whether any line already mentions
 *    `rfc3442-classless-static-routes` and skipping the insertion entirely if so - same check writeRoute() now
 *    makes before it touches the real file.
 *
 * Nothing here starts, stops, or restarts any daemon, and nothing here writes to any file under /var/opt/conn
 * itself - only to a scratch copy under /tmp that it deletes in the same command.
 *
 * Also lists the whole /var/opt/conn directory, not just the one config file - added after a real run of
 * ZgwBridgeApply.revertRoute() reported "no backup found" and it was only visible, after the fact, in the app's
 * own exec log that this was actually a missing-`head` problem (ls -t ... | head -1 failing outright on this
 * shell) rather than the backup genuinely being gone. A full listing here means that distinction - a backup file
 * that's really not there, versus one sitting right there that a command just failed to find - is visible
 * directly, without having to read the app's own log to work it out.
 */
object ZgwBridgeDiag {
    private val script = """
        echo "== the real config file, as it stands today =="
        cat /var/opt/conn/dhcpd.uap0.conf 2>&1
        echo
        echo "== its age/size (how long since it was last written) =="
        ls -la /var/opt/conn/dhcpd.uap0.conf 2>&1
        echo
        echo "== everything in /var/opt/conn right now (backups included, if any) =="
        ls -la /var/opt/conn/ 2>&1
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
        echo "== dry run, take 2: declare the option code this old dhcpd doesn't predefine, then use it =="
        echo "   (built from the REAL file, line by line; written only to /tmp; deleted again right after)"
        already=no
        while IFS= read -r zgwline; do
          case "${'$'}zgwline" in
            *rfc3442-classless-static-routes*) already=yes ;;
          esac
        done < /var/opt/conn/dhcpd.uap0.conf
        if [ "${'$'}already" = yes ]; then
          echo "Already applied - the real file already has the route. No insertion needed; nothing written."
        else
          rm -f /tmp/dhcpd.uap0.conf.zgwtest2 2>&1
          while IFS= read -r zgwline; do
            echo "${'$'}zgwline" >> /tmp/dhcpd.uap0.conf.zgwtest2
            case "${'$'}zgwline" in
              "ddns-update-style none;")
                echo "option rfc3442-classless-static-routes code 121 = array of integer 8;" >> /tmp/dhcpd.uap0.conf.zgwtest2
                ;;
              "   option domain-name-servers 172.16.224.1;")
                echo "   option rfc3442-classless-static-routes 16, 192, 168, 172, 16, 224, 1;" >> /tmp/dhcpd.uap0.conf.zgwtest2
                ;;
            esac
          done < /var/opt/conn/dhcpd.uap0.conf
          echo "-- candidate file --"
          cat /tmp/dhcpd.uap0.conf.zgwtest2 2>&1
          echo "-- dhcpd -t result --"
          dhcpd -t -cf /tmp/dhcpd.uap0.conf.zgwtest2 2>&1
          rm -f /tmp/dhcpd.uap0.conf.zgwtest2 2>&1
          echo "(scratch file removed)"
        fi
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
