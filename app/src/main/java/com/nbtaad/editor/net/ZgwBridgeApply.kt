package com.nbtaad.editor.net

/**
 * The real write step - test branch only, gated behind its own separate, explicitly-confirmed action, never
 * folded into the existing reset flow. See ZgwBridgeDiag's doc comment for the background and the dry run this
 * was built from, and the conversation itself for why this is split the way it is.
 *
 * Confirmed against the real car (not a guess): this dhcpd build (isc-dhcpd-4.1-ESV-R3) parses the corrected
 * two-line candidate cleanly. What's below reuses that exact, already-dry-run-tested insertion logic, but now
 * aimed at the real file instead of a /tmp scratch copy.
 *
 * Deliberately split into two concerns that are NOT both done here:
 *
 * 1. writeRoute() - backs up the real file, builds the candidate, and ONLY swaps it in (atomically, via `mv`) if
 *    dhcpd's own `-t` syntax check on the candidate passes first. If it fails, the real file is never touched and
 *    the (unused) backup stays as a harmless record. This part is genuinely low-risk: a small text file write,
 *    syntax-validated before it goes anywhere near the live path, with an automatic backup.
 *    A real run caught one bug here: this shell's `date` doesn't understand `+%s` and just echoes the literal
 *    token back (exit 0, so a plain `|| echo unknown` fallback never fires) - meaning every backup was landing at
 *    the exact same literal path `....conf.bak.%s`, which would silently overwrite the one clean backup on a
 *    second write. Fixed by validating the output is actually all-digits (`case ... ''|*[!0-9]*)`) and falling
 *    back to the shell's own PID (`$$`) - always numeric, always available, no dependency on `date` at all.
 *
 * 2. Restarting dhcpd so it actually notices the new file - deliberately NOT done here. ISC dhcpd doesn't reliably
 *    hot-reload, so making this edit "live" means either (a) killing and relaunching the running dhcpd process over
 *    this same SSH connection, or (b) relying on whatever already rewrites this file every 20-30 minutes in this
 *    car's own observed behavior (twice now, each time in sync with a phone's lease renewing) to also be the thing
 *    that restarts dhcpd itself, and just waiting for that. Option (a) is the same category of action - killing and
 *    relaunching a running process on this head unit over SSH - as the `sysshutdown`/`OnOffDSICommander` methods
 *    that have already hung this exact head unit twice. That doesn't make it certain to fail here too (dhcpd is a
 *    small, simple daemon, not the whole HMI), but it's close enough in kind that it deserves its own explicit
 *    decision rather than being silently bundled in alongside the much safer file-write step. Not wired up either
 *    way yet.
 *
 * revertRoute() is the undo for writeRoute() only - it restores the most recent backup. It does not touch dhcpd
 * either, for the same reason.
 */
object ZgwBridgeApply {
    private const val CONF = "/var/opt/conn/dhcpd.uap0.conf"

    /**
     * Backs up [CONF], builds the corrected candidate from that backup (same two insertion points ZgwBridgeDiag's
     * dry run already proved), and swaps it in only if `dhcpd -t` accepts the candidate. Throws (does not catch)
     * if the SSH connection itself fails - same convention as every other direct SSH call in this package.
     */
    fun writeRoute(host: String): String {
        val script = """
            TS=${'$'}(date +%s 2>/dev/null)
            case "${'$'}TS" in ''|*[!0-9]*) TS=${'$'}${'$'} ;; esac
            BAK="$CONF.bak.${'$'}TS"
            echo "== backing up the real config =="
            cp $CONF "${'$'}BAK" 2>&1
            ls -la "${'$'}BAK" 2>&1
            echo
            echo "== building the corrected candidate from that backup =="
            rm -f $CONF.new 2>&1
            while IFS= read -r zgwline; do
              echo "${'$'}zgwline" >> $CONF.new
              case "${'$'}zgwline" in
                "ddns-update-style none;")
                  echo "option rfc3442-classless-static-routes code 121 = array of integer 8;" >> $CONF.new
                  ;;
                "   option domain-name-servers 172.16.224.1;")
                  echo "   option rfc3442-classless-static-routes 16, 192, 168, 172, 16, 224, 1;" >> $CONF.new
                  ;;
              esac
            done < "${'$'}BAK"
            echo "-- syntax check on the candidate before it goes anywhere near the real file --"
            if dhcpd -t -cf $CONF.new 2>&1; then
              mv $CONF.new $CONF 2>&1
              echo
              echo "SWAPPED IN. Backup kept at ${'$'}BAK."
              echo "dhcpd itself was NOT restarted or signalled - the running process still has the OLD config in"
              echo "memory until it re-reads the file on its own, or is told to. This is deliberate - see the"
              echo "doc comment on ZgwBridgeApply for why that step is being held back on purpose."
            else
              echo
              echo "ABORTED: the candidate failed its own syntax check. The real file was never touched."
              echo "The backup at ${'$'}BAK is harmless and unused - safe to ignore or delete."
              rm -f $CONF.new 2>&1
            fi
        """.trimIndent()
        val out = SshSession(host).use { ssh -> ssh.exec(script, timeoutMs = 20_000).text }
        return "Read/written over SSH from $host.\n\n" + out.trimEnd() + "\n"
    }

    /**
     * Restores the most recent `writeRoute()` backup over [CONF]. Does not touch dhcpd, for the same reason
     * `writeRoute()` doesn't. Throws (does not catch) if the SSH connection itself fails.
     */
    fun revertRoute(host: String): String {
        val script = """
            BAK=${'$'}(ls -t $CONF.bak.* 2>/dev/null | head -1)
            echo "== most recent backup =="
            echo "${'$'}BAK"
            if [ -n "${'$'}BAK" ]; then
              cp "${'$'}BAK" $CONF 2>&1
              echo "RESTORED from ${'$'}BAK."
              echo "dhcpd itself was NOT restarted - same reason as writeRoute()."
            else
              echo "No backup found on this head unit - nothing to revert."
            fi
        """.trimIndent()
        val out = SshSession(host).use { ssh -> ssh.exec(script, timeoutMs = 20_000).text }
        return "Read/written over SSH from $host.\n\n" + out.trimEnd() + "\n"
    }
}
