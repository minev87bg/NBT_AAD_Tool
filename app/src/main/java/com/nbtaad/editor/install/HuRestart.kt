package com.nbtaad.editor.install

import com.nbtaad.editor.log.AppLog

/**
 * Restarts the head unit through its shell, for the car's Android Auto WiFi, where the car's gateway (the way the other
 * reset reaches the head unit) cannot be reached. The job runs in hu-omap's shell like the tile's and the menu look's.
 * It looks in the places a QNX system keeps its commands, and prefers a restart of the whole head unit: `shutdown -b`.
 * Without that command it uses the one Sq00ky's install guide gives after a plugin is installed, `OnOffDSICommander
 * appreset`, which restarts only the HMI (the iDrive software): the report says which of the two it was (`ST kind full` or
 * `ST kind hmi`), because only a full restart runs the start files again (the tile's boot hook is one). Both are run after a
 * few seconds' delay, so that the report can be read first. Neither command needs to sit in one of the usual places: when
 * it is not, a plain, read-only search of the whole file system (not stopping at the edge of one disk, since `/opt` and
 * the others can each be their own mounted place on a system like this) looks for it by name before giving up. The search
 * proves itself first, against a file already known to be there (the plugin's own program): if that is not found either,
 * the search itself cannot be trusted, and the report says so plainly instead of claiming the two commands are absent
 * when the truth may just be that this shell's `find` cannot be used this way. This costs nothing when the fast check
 * above already found the command; a head unit with neither, anywhere a working search can reach, says so, and the person
 * is told to hold the power button, which is what the projects' guides say anyway.
 */
object HuRestart {
    private val places = listOf("/sbin", "/usr/sbin", "/bin", "/usr/bin", "/proc/boot", "/usr/local/bin")

    /** [root] and [stage] are "" and the staging folder on the head unit; [delaySeconds] is the wait before the command runs. */
    fun job(root: String = "", stage: String = TileScripts.stageThere(), delaySeconds: Int = 4): String {
        require(root.none { it in "'\"\\\n ¤" } && stage.none { it in "'\"\\\n ¤" })
        require(delaySeconds in 0..60)
        val list = places.joinToString(" ")
        return TileScripts.sh(
            """
            R=$root
            S=¤{1:-$stage}
            exec > ¤S/out 2>&1
            echo "JOB restart"
            CMD=""
            APP=""
            for d in $list; do
              if [ -f ¤R¤d/shutdown ]; then
                echo "INFO shutdown is in ¤d"
                [ -z "¤CMD" ] && CMD=¤R¤d/shutdown
              fi
              if [ -f ¤R¤d/OnOffDSICommander ]; then
                echo "INFO OnOffDSICommander is in ¤d"
                [ -z "¤APP" ] && APP=¤R¤d/OnOffDSICommander
              fi
            done
            SEARCHOK=""
            if [ -z "¤CMD" ] || [ -z "¤APP" ]; then
              PROOF=¤(find ¤R/opt -maxdepth 3 -type f -name nbt-aad 2>/dev/null | head -1)
              [ -n "¤PROOF" ] && SEARCHOK=yes
              echo "INFO a wider search's own test (looking for the plugin's own program, already known to be in ¤R/opt/aa) found [¤PROOF], so the search itself ¤([ -n "¤SEARCHOK" ] && echo can || echo cannot) be trusted here"
            fi
            if [ -z "¤CMD" ] && [ -n "¤SEARCHOK" ]; then
              F=¤(find ¤R/ -maxdepth 9 -type f -name shutdown 2>/dev/null | head -1)
              if [ -n "¤F" ]; then echo "INFO shutdown found by a wider search, at ¤F"; CMD=¤F; fi
            fi
            if [ -z "¤APP" ] && [ -n "¤SEARCHOK" ]; then
              F=¤(find ¤R/ -maxdepth 9 -type f -name OnOffDSICommander 2>/dev/null | head -1)
              if [ -n "¤F" ]; then echo "INFO OnOffDSICommander found by a wider search, at ¤F"; APP=¤F; fi
            fi
            if [ -n "¤CMD" ]; then
              ( trap '' 1; sleep $delaySeconds; ¤CMD -b ) < /dev/null > /dev/null 2>&1 &
              echo "ST kind full"
              echo "OK the head unit is asked to restart in $delaySeconds seconds (shutdown -b)"
              echo "END 0"
              exit 0
            fi
            if [ -n "¤APP" ]; then
              ( trap '' 1; sleep $delaySeconds; ¤APP appreset ) < /dev/null > /dev/null 2>&1 &
              echo "ST kind hmi"
              echo "OK the head unit's HMI is asked to restart in $delaySeconds seconds (OnOffDSICommander appreset)"
              echo "END 0"
              exit 0
            fi
            if [ -n "¤SEARCHOK" ]; then
              for n in reboot poweroff halt; do
                F=¤(find ¤R/ -maxdepth 9 -type f -name "¤n" 2>/dev/null | head -1)
                [ -n "¤F" ] && echo "INFO by the way, ¤n exists at ¤F too (not tried: not known to be safe to use here)"
              done
            fi
            echo "ERR $NO_COMMAND"
            echo "END 1"
            exit 1
            """,
        )
    }

    const val NO_COMMAND = "This head unit's shell has no restart command (shutdown or OnOffDSICommander), so it cannot be restarted from here. Hold the power button for 30 seconds instead."

    /** Asks the head unit at [host] to restart. A report with an `error` says why it was not asked; an exception, that it could not be reached. */
    fun ask(host: String): TileScripts.Report {
        val rep = Reporter(say = { _, _ -> }, progress = { _, _, _ -> })
        val r = HuJob.run(host, "restart", emptyList(), job(), remount = false, waitMs = 60_000, rep = rep)
        AppLog.i("Reset", "The head unit's shell was asked to restart: error ${r.error ?: "none"}, ${(r.ok + r.info).joinToString("; ")}")
        return r
    }
}
