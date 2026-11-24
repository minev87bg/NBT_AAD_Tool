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
 *    A second real run (the dry-run diagnostic, re-run after the edit was already live) caught a second bug:
 *    re-running the same anchor-based insertion against an already-edited file duplicates both lines, since the
 *    anchor lines (`ddns-update-style none;`, the `option domain-name-servers ...;` line) are still there - only
 *    added after, never replaced. Fixed by checking first whether the real file already mentions
 *    `rfc3442-classless-static-routes` anywhere and skipping the whole backup+insert+swap if so, reporting
 *    "already applied" instead. Idempotent now: tapping WRITE ROUTE twice in a row does nothing the second time.
 *
 * 2. restartDhcpd() - the step held back until now. ISC dhcpd doesn't reliably hot-reload, so making an edit "live"
 *    means actually killing and relaunching the running process over this same SSH connection - the same category
 *    of action as the `sysshutdown`/`OnOffDSICommander` methods that have already hung this exact head unit twice.
 *    Decided against waiting for the car's own apparent file-regeneration to do it instead: if that regeneration is
 *    tied to the AA WiFi service restarting at ignition-on (consistent with the file sitting untouched for 30+
 *    minutes of idle use, then changing right after a reconnect), the SAME event would also reset the file back to
 *    its original, unedited content - meaning passive waiting could mean the edit is wiped at the exact moment
 *    dhcpd would've had a chance to serve it, never actually reaching a live server. Wired behind its own separate
 *    confirmation in the UI, distinct from WRITE ROUTE's.
 *
 * revertRoute() is the undo for writeRoute() only - it restores the most recent backup. It does not touch dhcpd.
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
            echo "== checking whether the route is already in the real file =="
            already=no
            while IFS= read -r zgwline; do
              case "${'$'}zgwline" in
                *rfc3442-classless-static-routes*) already=yes ;;
              esac
            done < $CONF
            if [ "${'$'}already" = yes ]; then
              echo "Already applied - nothing written. (REVERT first if you want a clean slate to re-apply onto.)"
            else
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

    /**
     * The held-back step: kills the running dhcpd on uap0 and relaunches it with the exact command line NetDiag's
     * pidin dump showed it running with, so it actually reads [CONF] fresh. Tries `nohup` first (checked at call
     * time, not assumed) so the relaunched process survives this SSH exec channel closing; falls back to plain
     * backgrounding, with that fact reported plainly, if this shell doesn't have `nohup`. The liveness check in
     * this same script only proves the process was alive before the channel closed - it does NOT prove it survived
     * the channel closing. The real test is a separate, later check (CHECK READINESS, or just pidin again).
     */
    fun restartDhcpd(host: String): String {
        val script = """
            echo "== dhcpd before restart =="
            OLDPID=${'$'}(cat /tmp/dhcpd.pid.uap0 2>/dev/null)
            echo "pid file says: ${'$'}OLDPID"
            pidin | grep -i dhcpd 2>&1
            echo
            if [ -n "${'$'}OLDPID" ]; then
              echo "== stopping it =="
              kill ${'$'}OLDPID 2>&1
              sleep 1
            fi
            echo
            echo "== relaunching =="
            if command -v nohup >/dev/null 2>&1; then
              nohup dhcpd -f -d -cf $CONF -lf /var/opt/conn/dhcpd.uap0.leases -pf /tmp/dhcpd.pid.uap0 uap0 >/dev/null 2>&1 &
              echo "launched via nohup (should survive this SSH session closing)"
            else
              dhcpd -f -d -cf $CONF -lf /var/opt/conn/dhcpd.uap0.leases -pf /tmp/dhcpd.pid.uap0 uap0 >/dev/null 2>&1 &
              echo "nohup not found on this shell - launched plain. Whether it survives this session closing is"
              echo "unconfirmed either way - check again in a few seconds with a fresh SSH connection to know."
            fi
            sleep 1
            NEWPID=${'$'}(cat /tmp/dhcpd.pid.uap0 2>/dev/null)
            echo
            echo "== right after relaunch, same session still open =="
            echo "pid file says: ${'$'}NEWPID"
            if [ -n "${'$'}NEWPID" ] && kill -0 ${'$'}NEWPID 2>/dev/null; then
              echo "alive right now - this does NOT prove it stays alive once this SSH session closes"
            else
              echo "WARNING: no live process found at that pid right after relaunch"
            fi
            pidin | grep -i dhcpd 2>&1
        """.trimIndent()
        val out = SshSession(host).use { ssh -> ssh.exec(script, timeoutMs = 20_000).text }
        return "Read/written over SSH from $host.\n\n" + out.trimEnd() + "\n"
    }
}
