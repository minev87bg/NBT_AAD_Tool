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
     */
    fun run(host: String, tag: String, files: List<Pair<String, ByteArray>>, job: String, remount: Boolean, waitMs: Long, rep: Reporter): TileScripts.Report =
        SshSession(host).use { ssh ->
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
            val r = ssh.exec(TileScripts.start(ram, remount, there), timeoutMs = waitMs)
            val report = TileScripts.parse(r.text)
            AppLog.i(
                "Tile",
                "$tag: finished ${report.finished}, error ${report.error ?: "none"}, rc ${report.rc ?: "-"}, remounted ${report.remounted ?: "n/a"}",
            )
            if (report.noReport) {
                throw IOException(
                    "The head unit's other processor (hu-omap) did not run the job, so nothing was changed. " +
                        "Is Android Auto's head unit (the NBT Evo) the one connected?",
                )
            }
            if (!report.finished && report.error == null) {
                throw IOException("The head unit's report stopped before the end, so it is not known what was changed. Look at the head unit again.")
            }
            report
        }

    private fun upload(ssh: SshSession, path: String, bytes: ByteArray) {
        val r = ssh.exec("cat > $path", stdin = bytes, timeoutMs = timeoutFor(bytes.size))
        if (r.exit != 0) {
            throw IOException("Sending ${path.substringAfterLast('/')} failed: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}.")
        }
    }
}
