package com.nbtaad.editor.install

import com.nbtaad.editor.log.AppLog

/**
 * Restarts the head unit through its shell, for the car's Android Auto WiFi, where the car's gateway (the way the other
 * reset reaches the head unit) cannot be reached. The job runs in hu-omap's shell like the tile's and the menu look's.
 * It looks in the places a QNX system keeps its commands, for a restart of the whole head unit: `shutdown -b` (some head
 * units name this `sysshutdown` instead; both are tried the same way, under whichever name is actually there). This is
 * run after a few seconds' delay, so that the report can be read first.
 *
 * Sq00ky's install guide also mentions `OnOffDSICommander appreset`, which is said to restart only the HMI (the iDrive
 * software) - but that guide only ever uses it once, by hand, right after a fresh install, in its own particular sequence;
 * the guide's own routine *update* instructions never use it; they tell the person to hold the power button instead. Tried
 * here as a general-purpose restart (on a head unit built in March 2018), it has locked up the screen and silently killed
 * SSH too, with the car's own WiFi the only thing still answering and the power button the only way back - worse than
 * simply not finding a restart command at all. So it is never run: the job only ever looks for it, to say in the report
 * that it is there (and, over time, which head units have it), never to use it. If a safe way to restart just the HMI is
 * ever confirmed - on this head unit's software or another's - it can be added back deliberately; until then, a head unit
 * without `shutdown`/`sysshutdown` anywhere this search reaches is told to hold the power button, the same as the guide.
 *
 * Neither command needs to sit in one of the usual places: when it is not, a plain, read-only search of the whole file
 * system (not stopping at the edge of one disk, since `/opt` and the others can each be their own mounted place on a
 * system like this) looks for it by name before giving up. The search proves itself first, against a file already known
 * to be there (the plugin's own program): if that is not found either, the search itself cannot be trusted, and the
 * report says so plainly instead of claiming the restart command is absent when the truth may just be that this shell's
 * `find` cannot be used this way. This costs nothing when the fast check above already found the command. The first try
 * ([wide] false) keeps this search to a sensible depth so it stays quick; when even that finds nothing, a second try
 * with [wide] true drops the depth limit and searches the whole file system, which costs much more time but can still
 * reach a command the first try's depth missed. A head unit with no restart command, anywhere a working search can
 * reach, says so, and the person is told to hold the power button, which is what the project's guides say anyway. The
 * report also carries the head unit's own software version (`/opt/sys/etc/nbt_version.txt`, when it is there), read-only
 * and for the report alone, so that if `OnOffDSICommander` is ever worth trying again, which software versions it was
 * seen on is already on hand.
 */
object HuRestart {
    private val places = listOf(
        "/sbin", "/usr/sbin", "/bin", "/usr/bin", "/proc/boot", "/usr/local/bin",
        "/opt/sys/bin", "/opt/car/bin", "/opt/core/bin",
    )

    /** The full-restart command's possible names: `shutdown` everywhere the guides mention it, `sysshutdown` on some head units. */
    private val shutdownNames = listOf("shutdown", "sysshutdown")

    /**
     * [root] and [stage] are "" and the staging folder on the head unit; [delaySeconds] is the wait before the command
     * runs; [wide] drops the depth limit on the file-system search (see the class doc) for a slower, more thorough retry.
     */
    fun job(root: String = "", stage: String = TileScripts.stageThere(), delaySeconds: Int = 4, wide: Boolean = false): String {
        require(root.none { it in "'\"\\\n ¤" } && stage.none { it in "'\"\\\n ¤" })
        require(delaySeconds in 0..60)
        val list = places.joinToString(" ")
        val names = shutdownNames.joinToString(" ")
        val shutdownTest = shutdownNames.joinToString(" -o ") { "-name $it" }
        val depthArg = if (wide) "" else "-maxdepth 9"
        return TileScripts.sh(
            """
            R=$root
            S=¤{1:-$stage}
            exec > ¤S/out 2>&1
            echo "JOB restart"
            VER=¤(head -1 ¤R/opt/sys/etc/nbt_version.txt 2>/dev/null)
            [ -n "¤VER" ] && echo "INFO head unit software: ¤VER"
            CMD=""
            CMDNAME=""
            APP=""
            for d in $list; do
              for n in $names; do
                if [ -f ¤R¤d/¤n ]; then
                  echo "INFO ¤n is in ¤d"
                  if [ -z "¤CMD" ]; then CMD=¤R¤d/¤n; CMDNAME=¤n; fi
                fi
              done
              if [ -z "¤APP" ] && [ -f ¤R¤d/OnOffDSICommander ]; then
                APP=¤R¤d/OnOffDSICommander
                echo "INFO OnOffDSICommander is in ¤d (not used: it has locked up a head unit's screen and SSH together, with the power button the only way back)"
              fi
            done
            SEARCHOK=""
            if [ -z "¤CMD" ]; then
              PROOF=¤(find ¤R/opt -maxdepth 3 -type f -name nbt-aad 2>/dev/null | head -1)
              [ -n "¤PROOF" ] && SEARCHOK=yes
              echo "INFO a wider search's own test (looking for the plugin's own program, already known to be in ¤R/opt/aa) found [¤PROOF], so the search itself ¤([ -n "¤SEARCHOK" ] && echo can || echo cannot) be trusted here"
            fi
            if [ -z "¤CMD" ] && [ -n "¤SEARCHOK" ]; then
              F=¤(find ¤R/ $depthArg -type f \( $shutdownTest \) 2>/dev/null | head -1)
              if [ -n "¤F" ]; then
                echo "INFO a restart command was found by a wider search, at ¤F"
                CMD=¤F
                case "¤F" in *sysshutdown) CMDNAME=sysshutdown ;; *) CMDNAME=shutdown ;; esac
              fi
            fi
            if [ -n "¤CMD" ]; then
              ( trap '' 1; sleep $delaySeconds; ¤CMD -b ) < /dev/null > /dev/null 2>&1 &
              echo "ST kind full"
              echo "OK the head unit is asked to restart in $delaySeconds seconds (¤CMDNAME -b)"
              echo "END 0"
              exit 0
            fi
            if [ -z "¤APP" ] && [ -n "¤SEARCHOK" ]; then
              F=¤(find ¤R/ $depthArg -type f -name OnOffDSICommander 2>/dev/null | head -1)
              [ -n "¤F" ] && echo "INFO OnOffDSICommander found by a wider search, at ¤F (not used, for the same reason)"
            fi
            if [ -n "¤SEARCHOK" ]; then
              for n in reboot poweroff halt; do
                F=¤(find ¤R/ $depthArg -type f -name "¤n" 2>/dev/null | head -1)
                [ -n "¤F" ] && echo "INFO by the way, ¤n exists at ¤F too (not tried: not known to be safe to use here)"
              done
            fi
            echo "ERR $NO_COMMAND"
            echo "END 1"
            exit 1
            """,
        )
    }

    const val NO_COMMAND = "This head unit's shell has no restart command (shutdown or sysshutdown) anywhere this search reached, so it cannot be restarted from here. Hold the power button for 30 seconds instead."

    /**
     * Asks the head unit at [host] to restart. A report with an `error` says why it was not asked; an exception, that
     * it could not be reached. [wide] asks for the slower, whole-file-system search (see the class doc) instead of the
     * normal depth-limited one - for a "try harder" retry after a normal attempt already came back with [NO_COMMAND].
     */
    fun ask(host: String, wide: Boolean = false): TileScripts.Report {
        val rep = Reporter(say = { _, _ -> }, progress = { _, _, _ -> })
        val r = HuJob.run(host, "restart", emptyList(), job(wide = wide), remount = false, waitMs = if (wide) 180_000 else 60_000, rep = rep)
        AppLog.i("Reset", "The head unit's shell was asked to restart (wide=$wide): error ${r.error ?: "none"}, ${(r.ok + r.info).joinToString("; ")}")
        return r
    }
}
