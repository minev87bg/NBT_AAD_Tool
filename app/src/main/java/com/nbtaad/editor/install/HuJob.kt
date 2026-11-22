package com.nbtaad.editor.install

import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.SshSession
import java.io.IOException

/**
 * Runs a job script in hu-omap's shell (see [TileScripts]): the files the job needs and the job itself are uploaded into
 * hu-omap's RAM, the login starts the job there and reads back its report. Used by the tile and by the menu look.
 */
object HuJob {
    private fun timeoutFor(bytes: Int): Long = 60_000L + bytes / 8

    /**
     * [files] are sent first, by name, into the RAM folder; [job] is the script text; [remount] makes the flash writable
     * before the job starts; [waitMs] is how long the job may run. A report that says the job stopped (`error`) is returned;
     * a job that did not run or did not finish is an exception.
     *
     * With [runId] the job is started in the background and asked about every few seconds, each time through a connection
     * of its own (so a connection that drops in between costs nothing but a few seconds): the job must have been made with
     * the same id as its report name (`out.<runId>`, see [TileScripts.job]). That is for a job that takes minutes.
     */
    fun run(
        host: String, tag: String, files: List<Pair<String, ByteArray>>, job: String, remount: Boolean, waitMs: Long, rep: Reporter,
        runId: String? = null,
    ): TileScripts.Report {
        require(runId == null || !remount) { "a job that is waited for in the background does not write to the flash" }
        var staged: String? = null
        val direct = SshSession(host).use { ssh ->
            rep.say("Preparing room in the head unit's memory…", Level.INFO)
            val prep = ssh.exec(TileScripts.prepare(), timeoutMs = 30_000).text
            val places = prep.lines().firstOrNull { it.startsWith("RAM ") }?.removePrefix("RAM ")?.trim()?.split(Regex("\\s+"))
            val tried = prep.lines().filter { it.startsWith("TRY ") }.joinToString("\n") { it.removePrefix("TRY ") }
            if (places == null || places.size != 2) {
                AppLog.w("Tile", "$tag: no place for the staging folder in the head unit's memory. The login said:\n${prep.trim()}")
                throw IOException(
                    "The head unit's memory could not be reached from this connection, so nothing was sent. " +
                        "The log says what each place answered.",
                )
            }
            if (tried.isNotEmpty()) AppLog.i("Tile", "$tag: staging in ${places[0]} (the places tried before it said:\n$tried)")
            else AppLog.i("Tile", "$tag: staging in ${places[0]}")
            val ram = places[0]
            val there = places[1]
            for ((name, bytes) in files) {
                upload(ssh, "$ram/$name", bytes)
                rep.say("Sent $name (${bytes.size} bytes).", Level.INFO)
            }
            upload(ssh, "$ram/job.sh", job.toByteArray(Charsets.UTF_8))
            if (runId != null) {
                val r = ssh.exec(TileScripts.startDetached(ram, there, runId), timeoutMs = 30_000)
                AppLog.i("Tile", "$tag: started in the background (run $runId): ${r.text.trim()}")
                staged = ram
                return@use null
            }
            val r = ssh.exec(TileScripts.start(ram, remount, there), timeoutMs = waitMs)
            val report = TileScripts.parse(r.text)
            finish(tag, report)
        }
        if (direct != null) return direct
        return waitFor(host, tag, staged!!, runId!!, waitMs, rep)
    }

    /** What every report is checked for, and the program's own log put in the app log (the page shows it, the log should have it too). */
    private fun finish(tag: String, report: TileScripts.Report): TileScripts.Report {
        AppLog.i(
            "Tile",
            "$tag: finished ${report.finished}, error ${report.error ?: "none"}, rc ${report.rc ?: "-"}, remounted ${report.remounted ?: "n/a"}",
        )
        if (report.log.isNotBlank()) logProgram(tag, report.log)
        if (report.noReport) {
            throw IOException(
                "The head unit's other processor (hu-omap) did not run the job, so nothing was changed. " +
                    "Is Android Auto's head unit (the NBT Evo) the one connected?",
            )
        }
        if (!report.finished && report.error == null) {
            throw IOException("The head unit's report stopped before the end, so it is not known what was changed. Look at the head unit again.")
        }
        return report
    }

    /** One log entry holds about 3000 characters, so the program's log (up to 20 000 characters) goes in as several. */
    private fun logProgram(tag: String, log: String) {
        val lines = log.trimEnd().lines()
        AppLog.i("Tile", "$tag: the program's log, ${lines.size} lines:")
        val chunk = StringBuilder()
        for (l in lines) {
            val line = if (l.length > 400) l.take(400) + "…" else l
            if (chunk.isNotEmpty() && chunk.length + line.length > 2700) {
                AppLog.i("Tile", "$tag: program log\n$chunk")
                chunk.clear()
            }
            chunk.append(line).append('\n')
        }
        if (chunk.isNotEmpty()) AppLog.i("Tile", "$tag: program log\n$chunk")
    }

    /** How often a background job is asked about, and for how long it may have no report at all before it is taken as not started. */
    private const val POLL_EVERY_MS = 3_000L
    private const val NO_REPORT_MS = 45_000L

    /**
     * Asks about the job started in the background until it is over. A connection that fails is not the end of it: the job
     * goes on in the head unit, so the next question is asked the same way a few seconds later, until the time is up.
     */
    private fun waitFor(host: String, tag: String, ram: String, id: String, waitMs: Long, rep: Reporter): TileScripts.Report {
        val began = System.currentTimeMillis()
        var lastAnswer = began
        var lost = false
        var seenReport = false
        while (System.currentTimeMillis() - began < waitMs) {
            Thread.sleep(POLL_EVERY_MS)
            val text = try {
                SshSession(host).use { it.exec(TileScripts.poll(ram, id), timeoutMs = 25_000).text }
            } catch (e: Exception) {
                if (!lost) AppLog.w("Tile", "$tag: no answer from the head unit while the job runs (${e.javaClass.simpleName}: ${e.message}); asking again")
                lost = true
                rep.say("The connection to the head unit dropped. The test goes on there. Waiting for the connection to come back…", Level.WARN)
                continue
            }
            if (lost) {
                AppLog.i("Tile", "$tag: the head unit answers again after ${(System.currentTimeMillis() - lastAnswer) / 1000} s")
                lost = false
            }
            lastAnswer = System.currentTimeMillis()
            when {
                text.contains(TileScripts.POLL_FINISHED) -> {
                    cleanUp(host, ram)
                    return finish(tag, TileScripts.parse(text.substringBefore(TileScripts.POLL_FINISHED)))
                }
                text.contains(TileScripts.POLL_RUNNING) -> seenReport = true
                !seenReport && System.currentTimeMillis() - began > NO_REPORT_MS -> {
                    cleanUp(host, ram)
                    return finish(tag, TileScripts.parse("NOREPORT"))
                }
            }
            rep.say("Test running on the head unit…", Level.INFO)
        }
        AppLog.w("Tile", "$tag: the job did not end in time (last answer ${(System.currentTimeMillis() - lastAnswer) / 1000} s ago)")
        throw IOException(
            if (lost) "The connection to the head unit was lost during the test and did not come back in time. The test itself ends by itself on the head unit."
            else "The head unit did not report the end of the test in time. The test itself ends by itself on the head unit.",
        )
    }

    private fun cleanUp(host: String, ram: String) {
        try {
            SshSession(host).use { it.exec(TileScripts.cleanup(ram), timeoutMs = 15_000) }
        } catch (e: Exception) {
            AppLog.w("Tile", "Clearing the staging folder failed (${e.message}); the next run replaces what is there")
        }
    }

    private fun upload(ssh: SshSession, path: String, bytes: ByteArray) {
        val r = ssh.exec("cat > $path", stdin = bytes, timeoutMs = timeoutFor(bytes.size))
        if (r.exit != 0) {
            throw IOException("Sending ${path.substringAfterLast('/')} failed: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}.")
        }
    }
}
