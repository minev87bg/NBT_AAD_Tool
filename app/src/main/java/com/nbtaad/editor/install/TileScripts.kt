package com.nbtaad.editor.install

/*
 * The shell scripts that put deathwalker80's nbt-aa-tile on the head unit, written from the steps of the author's own
 * test-ram.sh, install-boot.sh and remove-boot.sh (the zip's scripts are not run: they look for a USB stick).
 *
 * Why a job file: the SSH login has few commands (no chmod, no `sh -n`, no wc), but the shell of hu-omap, the
 * processor Android Auto runs on, has them: it is the shell the author's guide asks for (`on -f /net/hu-omap/ /bin/sh`).
 * So the program, the launcher and the hook are uploaded into hu-omap's RAM (a folder in /dev/shmem, never on the
 * flash), together with one job script made here; the login starts the job in hu-omap's shell and reads back the
 * report the job wrote into the same folder (`on` shows nothing of what the job prints, which is why it is a file).
 *
 * The job's report is made of lines: `ST key value` (what is there now), `OK text`, `INFO text`, `WARN text`,
 * `ERR text` (the job stopped, with the reason), `RC n` (the exit code of the test run), `LOG-BEGIN`/`LOG-END` around the
 * program's own log, and last `END n`. A report without END means the job did not finish.
 *
 * Tests run the job with this machine's sh on a folder made like a head unit: [job] takes the root and the folder of
 * the staged files as arguments, which are "" and /dev/shmem/nbt-aad-tool-tile on the head unit.
 */
object TileScripts {
    const val STAGE_DIR = "nbt-aad-tool-tile"

    /** The launcher is told by the job to start in this mode; the program itself takes the same words. */
    enum class Mode(val word: String, val label: String) {
        FOLLOW("follow", "Only while Android Auto is connected"),
        ALWAYS("always", "Always shown");

        companion object {
            fun of(word: String?): Mode = entries.firstOrNull { it.word == word } ?: FOLLOW
        }
    }

    enum class Action(val word: String) { STATUS("status"), TEST("test"), INSTALL("install"), REMOVE("remove") }

    /** How long the test run keeps the tile on the iDrive (the program's own time limit, as in the author's test-ram.sh). */
    const val TEST_SECONDS = 120

    private fun sh(body: String): String = (RemoteScripts.pathFix + body.trimIndent()).replace('¤', '$')

    // ---- on the SSH login ----

    /** Finds hu-omap's RAM as this login names it, makes the staging folder in it and empties it. Prints `RAM <folder>` or `NORAM`. */
    fun prepare(): String = sh(
        """
        RAM=/net/hu-omap/dev/shmem
        if [ ! -d ¤RAM ]; then RAM=/dev/shmem; fi
        mkdir ¤RAM/$STAGE_DIR 2>/dev/null
        rm -f ¤RAM/$STAGE_DIR/aa-tile ¤RAM/$STAGE_DIR/aa-tile-launcher.sh ¤RAM/$STAGE_DIR/hook.txt ¤RAM/$STAGE_DIR/job.sh ¤RAM/$STAGE_DIR/out
        if [ -d ¤RAM/$STAGE_DIR ]; then echo "RAM ¤RAM/$STAGE_DIR"; else echo "NORAM"; fi
        exit 0
        """
    )

    /** The folder the job sees as its own: hu-omap's RAM is /dev/shmem there. */
    fun stageThere(): String = "/dev/shmem/$STAGE_DIR"

    /**
     * Starts the job in hu-omap's shell and prints its report. [ram] is what [prepare] printed. [remount] first makes
     * hu-omap's flash writable, as the author's guide does before `on -f /net/hu-omap/ /bin/sh`; it is needed to install
     * or remove, not to look or test.
     */
    fun start(ram: String, remount: Boolean): String {
        val job = "${stageThere()}/job.sh"
        val run = if (ram.startsWith("/net/hu-omap/")) "on -f /net/hu-omap/ /bin/sh $job" else "/bin/sh $job"
        return sh(
            (if (remount) RemoteScripts.REMOUNT_PART + "\n" else "") +
                """
                rm -f $ram/out
                $run < /dev/null > /dev/null 2>&1
                if [ -f $ram/out ]; then cat $ram/out; else echo "NOREPORT"; fi
                rm -f $ram/aa-tile $ram/aa-tile-launcher.sh $ram/hook.txt $ram/job.sh $ram/out
                exit 0
                """
        )
    }

    // ---- on hu-omap ----

    /**
     * The job. [root] and [stage] are "" and the staging folder on the head unit; [programSize], [launcherSize] and
     * [hookSize] are the sizes of the three files as they were sent, which the job checks them against.
     */
    fun job(
        action: Action,
        mode: Mode = Mode.FOLLOW,
        programSize: Int = 0,
        launcherSize: Int = 0,
        hookSize: Int = 0,
        root: String = "",
        stage: String = stageThere(),
    ): String {
        require(programSize >= 0 && launcherSize >= 0 && hookSize >= 0)
        require(root.none { it in "'\"\\\n ¤" } && stage.none { it in "'\"\\\n ¤" })
        val head = """
            R=$root
            S=$stage
            exec > ¤S/out 2>&1
            W=¤R/opt/conn/bin/start_webserver.sh
            D=¤R/opt/aa-tile
            B=¤D/start_webserver.sh.original
            N=¤D/start_webserver.sh.new
            V=¤R/var/opt/aa-tile
            SH=¤R/dev/shmem
            MODE=${mode.word}
            PS=$programSize
            LS=$launcherSize
            HS=$hookSize
            SEC=$TEST_SECONDS
            echo "JOB ${action.word}"
        """
        return sh(head + "\n" + COMMON + "\n" + body(action) + "\nexit 0")
    }

    private val COMMON = """
        fail() { echo "ERR ¤1"; echo "END 1"; exit 1; }
        size() { Z=¤(wc -c < "¤1" 2>/dev/null); echo ¤Z; }
        hooked() { grep 'nbt-aa-tile hook' ¤W >/dev/null 2>&1; }
        running() { pidin 2>/dev/null | grep 'aa-tile/aa-tile' | grep -v grep >/dev/null 2>&1; }
        state() {
          if [ -f ¤W ]; then echo "ST webserver yes"; else echo "ST webserver no"; fi
          if hooked; then echo "ST hook yes"; else echo "ST hook no"; fi
          if grep 'casella-aa hook' ¤W >/dev/null 2>&1; then echo "ST casella yes"; else echo "ST casella no"; fi
          if [ -f ¤D/aa-tile ]; then echo "ST program ¤(size ¤D/aa-tile)"; else echo "ST program none"; fi
          if [ -f ¤D/aa-tile-launcher.sh ]; then echo "ST launcher yes"; else echo "ST launcher no"; fi
          if [ -f ¤B ]; then echo "ST original yes"; else echo "ST original no"; fi
          if [ -f ¤SH/nbt-aad-state ]; then echo "ST aad running"; else echo "ST aad stopped"; fi
          if running; then echo "ST tile running"; else echo "ST tile stopped"; fi
          for f in DISABLED CRASHED boots mode last; do
            if [ -f ¤V/¤f ]; then x=""; read x < ¤V/¤f; echo "ST ¤f ¤x"; fi
          done
        }
        stop_tile() {
          if running; then
            pidin 2>/dev/null | grep 'aa-tile/aa-tile' | grep -v grep | while read p r; do kill ¤p; done
            echo "INFO the running aa-tile was stopped"
          fi
        }
        [ -d ¤R/opt/hmi ] || fail "This is not the shell of the head unit's hu-omap processor (no /opt/hmi), so nothing was changed."
        state
    """.trimIndent()

    private fun body(a: Action): String = when (a) {
        Action.STATUS -> "echo \"OK looked\"\necho \"END 0\""
        Action.TEST -> TEST
        Action.INSTALL -> INSTALL
        Action.REMOVE -> REMOVE
    }

    /** The author's test-ram.sh: the program runs from RAM for a fixed time, then ends by itself and hides the tile. */
    private val TEST = """
        [ -f ¤S/aa-tile ] || fail "aa-tile did not arrive on the head unit."
        [ "¤(size ¤S/aa-tile)" = "¤PS" ] || fail "aa-tile arrived with the wrong size (¤(size ¤S/aa-tile) bytes, not ¤PS). It was not started."
        P=¤SH/aa-tile
        L=¤SH/aa-tile.log
        rm -f ¤P ¤L
        # the stage is not executable: copy a program (its execute bit stays), then write the file's content over it
        cp /bin/ls ¤P && cat ¤S/aa-tile > ¤P || { rm -f ¤P; fail "Cannot copy aa-tile into RAM."; }
        [ "¤(size ¤P)" = "¤PS" ] || { rm -f ¤P; fail "The copy of aa-tile in RAM has the wrong size."; }
        echo "RUN ¤MODE ¤SEC"
        ¤P ¤MODE ¤SEC > ¤L 2>&1
        RC=¤?
        echo "LOG-BEGIN"
        cat ¤L 2>/dev/null
        echo "LOG-END"
        echo "RC ¤RC"
        rm -f ¤P ¤L
        echo "END 0"
    """.trimIndent()

    /** The author's install-boot.sh. */
    private val INSTALL = """
        [ -f ¤W ] || fail "¤W is not on this head unit: this is different software, so nothing was changed."
        if grep 'casella-aa hook' ¤W >/dev/null 2>&1; then
          fail "The older casella-aa build is installed. Remove it first with its own remove script, restart, then try again."
        fi
        for f in aa-tile aa-tile-launcher.sh hook.txt; do
          [ -f ¤S/¤f ] || fail "¤f did not arrive on the head unit."
        done
        [ "¤(size ¤S/aa-tile)" = "¤PS" ] || fail "aa-tile arrived with the wrong size, nothing was changed."
        [ "¤(size ¤S/aa-tile-launcher.sh)" = "¤LS" ] || fail "aa-tile-launcher.sh arrived with the wrong size, nothing was changed."
        [ "¤(size ¤S/hook.txt)" = "¤HS" ] || fail "hook.txt arrived with the wrong size, nothing was changed."
        mkdir -p ¤D || fail "Cannot make ¤D. Is the head unit's system partition writable?"
        stop_tile
        # the program: a program's copy keeps its execute bit, the new content is written over it, then it takes the old one's place
        rm -f ¤D/aa-tile.new
        cp /bin/ls ¤D/aa-tile.new && cat ¤S/aa-tile > ¤D/aa-tile.new || { rm -f ¤D/aa-tile.new; fail "Copying aa-tile failed. Is there room, and is the partition writable?"; }
        chmod 755 ¤D/aa-tile.new 2>/dev/null
        [ "¤(size ¤D/aa-tile.new)" = "¤PS" ] || { rm -f ¤D/aa-tile.new; fail "The copy of aa-tile is not complete."; }
        mv ¤D/aa-tile.new ¤D/aa-tile || fail "Could not put aa-tile in place."
        cat ¤S/aa-tile-launcher.sh > ¤D/aa-tile-launcher.sh || fail "Copying the launcher failed."
        [ "¤(size ¤D/aa-tile-launcher.sh)" = "¤LS" ] || fail "The copy of the launcher is not complete."
        echo "OK aa-tile and its launcher are in ¤D"
        if hooked; then
          echo "OK the start line is already in start_webserver.sh: only the program and the launcher were updated"
        else
          # without our lines the file is the head unit's own, so the backup is always taken from it now
          cp ¤W ¤B || fail "The backup of start_webserver.sh failed. Nothing was changed there."
          [ "¤(size ¤B)" = "¤(size ¤W)" ] || { rm -f ¤B; fail "The backup of start_webserver.sh is not complete. Nothing was changed there."; }
          echo "OK the original start_webserver.sh is kept as ¤B"
          { echo '#! /bin/sh'; cat ¤S/hook.txt; cat ¤B; } > ¤N || { rm -f ¤N; fail "Cannot write ¤N."; }
          [ "¤(size ¤N)" = "¤((11 + HS + ¤(size ¤B)))" ] || { rm -f ¤N; fail "The new start_webserver.sh is not the size it should be. Nothing was changed."; }
          sh -n ¤N || { rm -f ¤N; fail "The new start_webserver.sh did not pass the syntax check. Nothing was changed."; }
          echo "OK the new start_webserver.sh passes the syntax check"
          cp ¤N ¤W || { cp ¤B ¤W; chmod 755 ¤W; fail "Replacing start_webserver.sh failed. The original is back."; }
          chmod 755 ¤W
          rm -f ¤N
          if hooked && [ "¤(size ¤W)" = "¤((11 + HS + ¤(size ¤B)))" ]; then
            echo "OK the start line is in start_webserver.sh"
          else
            cp ¤B ¤W; chmod 755 ¤W
            fail "start_webserver.sh was not right after the copy. The original is back."
          fi
        fi
        mkdir -p ¤V
        rm -f ¤V/DISABLED ¤V/CRASHED ¤V/boots
        echo ¤MODE > ¤V/mode
        echo "OK mode ¤MODE"
        echo "END 0"
    """.trimIndent()

    /** The author's remove-boot.sh. */
    private val REMOVE = """
        stop_tile
        if hooked; then
          if [ ! -s ¤B ]; then fail "The start line is in start_webserver.sh, but the saved original ¤B is missing. start_webserver.sh was not touched."; fi
          if grep 'nbt-aa-tile hook' ¤B >/dev/null 2>&1; then fail "The saved original ¤B has the start line in it. start_webserver.sh was not touched."; fi
          cp ¤B ¤W || fail "Restoring start_webserver.sh failed."
          chmod 755 ¤W
          if hooked; then fail "The start line is still in start_webserver.sh."; fi
          echo "OK the original start_webserver.sh is back"
        else
          echo "INFO start_webserver.sh has no start line of the tile"
        fi
        rm -f ¤D/aa-tile ¤D/aa-tile.new ¤D/aa-tile-launcher.sh
        rm -rf ¤V
        echo "OK the program and its counters are removed (the saved original stays in ¤D)"
        echo "END 0"
    """.trimIndent()

    // ---- the report ----

    /** What a job said. */
    class Report(
        /** `ST` lines: key to value. */
        val state: Map<String, String>,
        val ok: List<String>,
        val info: List<String>,
        val warn: List<String>,
        val error: String?,
        /** The exit code of the test run, when there was one. */
        val rc: Int?,
        val log: String,
        /** `END` was printed: the job ran to its last line. */
        val finished: Boolean,
        /** The login found no report file at all. */
        val noReport: Boolean,
        /** Whether the flash was made writable first (null: it was not asked to be). */
        val remounted: Boolean? = null,
    ) {
        val success: Boolean get() = finished && error == null && !noReport
        fun has(key: String, value: String): Boolean = state[key] == value

        /** What the head unit has of the tile now. */
        val hooked: Boolean get() = state["hook"] == "yes"
        val programHere: Boolean get() = state["program"]?.let { it != "none" } == true
        val tileRunning: Boolean get() = state["tile"] == "running"
        val aadRunning: Boolean get() = state["aad"] == "running"
        val disabled: Boolean get() = state.containsKey("DISABLED")
        val crashed: Boolean get() = state.containsKey("CRASHED")
        val mode: String? get() = state["mode"]?.takeIf { it.isNotBlank() }
        val last: String? get() = state["last"]?.takeIf { it.isNotBlank() }
        val casella: Boolean get() = state["casella"] == "yes"
    }

    fun parse(text: String): Report {
        val state = LinkedHashMap<String, String>()
        val ok = ArrayList<String>()
        val info = ArrayList<String>()
        val warn = ArrayList<String>()
        var error: String? = null
        var rc: Int? = null
        var finished = false
        val log = StringBuilder()
        var inLog = false
        var remounted: Boolean? = null
        var noReport = false
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            if (inLog) {
                if (line == "LOG-END") inLog = false else if (log.length < 20_000) log.append(line).append('\n')
                continue
            }
            when {
                line == "LOG-BEGIN" -> inLog = true
                line == "NOREPORT" -> noReport = true
                line == "REMOUNT ok" -> remounted = true
                line == "REMOUNT failed" -> remounted = false
                line.startsWith("ST ") -> line.removePrefix("ST ").split(' ', limit = 2).let { state[it[0]] = it.getOrElse(1) { "" }.trim() }
                line.startsWith("OK ") -> ok += line.removePrefix("OK ")
                line.startsWith("INFO ") -> info += line.removePrefix("INFO ")
                line.startsWith("WARN ") -> warn += line.removePrefix("WARN ")
                line.startsWith("ERR ") -> error = line.removePrefix("ERR ")
                line.startsWith("RC ") -> rc = line.removePrefix("RC ").trim().toIntOrNull()
                line.startsWith("END ") -> finished = true
            }
        }
        return Report(state, ok, info, warn, error, rc, log.toString(), finished, noReport, remounted)
    }

    /** What the program's exit code means (from the author's test-ram.sh). */
    fun exitMeaning(rc: Int?): String = when (rc) {
        0 -> "The iDrive saw the device and subscribed to it."
        2 -> "The program registered, but the iDrive did not subscribe (so no tile)."
        4 -> "The program could not attach or register to the MOST server."
        1 -> "The program could not start (a library or symbol is missing)."
        9 -> "The program crashed. Keep the log. Do not install it."
        null -> "The test did not report an exit code."
        else -> "Unexpected exit code $rc."
    }
}
