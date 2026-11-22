package com.nbtaad.editor.install

import com.nbtaad.editor.addon.TileKit
import com.nbtaad.editor.addon.TileKits
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.SshSession
import java.io.IOException

/**
 * Looks at, tests, installs and removes nbt-aa-tile on the head unit (the steps themselves are in [TileScripts]).
 * What is sent goes into hu-omap's RAM first; the only thing written to the flash is by an install, and it is the
 * three files the author's own install script writes plus the start line in start_webserver.sh.
 */
object TileHeadUnit {
    private fun timeoutFor(bytes: Int): Long = 60_000L + bytes / 8

    /**
     * Runs [action] on the head unit at [host]. [kit] is needed for a test and an install (its files are sent first).
     * Returns what the head unit reported; a report that says the job stopped (`error`) is not an exception, so that the
     * caller can show what the head unit still has.
     */
    fun run(host: String, action: TileScripts.Action, kit: TileKit?, mode: TileScripts.Mode, rep: Reporter): TileScripts.Report {
        val sending = action == TileScripts.Action.TEST || action == TileScripts.Action.INSTALL
        if (sending) require(kit != null && kit.ok) { "The tile files are not complete." }
        return SshSession(host).use { ssh ->
            rep.say("Preparing room in the head unit's memory…", Level.INFO)
            val prep = ssh.exec(TileScripts.prepare(), timeoutMs = 30_000).text
            val ram = prep.lines().firstOrNull { it.startsWith("RAM ") }?.removePrefix("RAM ")?.trim()
                ?: throw IOException("The head unit's memory could not be reached from this connection, so nothing was sent.")

            if (sending) {
                val k = kit!!
                for ((name, bytes) in listOf(
                    TileKits.PROGRAM to k.program, TileKits.LAUNCHER to k.launcher, TileKits.HOOK to k.hook,
                )) {
                    upload(ssh, "$ram/$name", bytes)
                    rep.say("Sent $name (${bytes.size} bytes).", Level.INFO)
                }
            }
            val job = TileScripts.job(
                action, mode,
                programSize = kit?.program?.size ?: 0,
                launcherSize = kit?.launcher?.size ?: 0,
                hookSize = kit?.hook?.size ?: 0,
            )
            upload(ssh, "$ram/job.sh", job.toByteArray(Charsets.UTF_8))

            rep.say(
                when (action) {
                    TileScripts.Action.STATUS -> "Looking at the head unit…"
                    TileScripts.Action.TEST -> "Test running on the head unit (${TileScripts.TEST_SECONDS} seconds)…"
                    TileScripts.Action.INSTALL -> "Installing on the head unit…"
                    TileScripts.Action.REMOVE -> "Removing from the head unit…"
                },
                Level.INFO,
            )
            val wait = if (action == TileScripts.Action.TEST) (TileScripts.TEST_SECONDS + 90) * 1000L else 90_000L
            val needsWrite = action == TileScripts.Action.INSTALL || action == TileScripts.Action.REMOVE
            val r = ssh.exec(TileScripts.start(ram, remount = needsWrite), timeoutMs = wait)
            val report = TileScripts.parse(r.text)
            AppLog.i(
                "Tile",
                "${action.word}: finished ${report.finished}, error ${report.error ?: "none"}, rc ${report.rc ?: "-"}, " +
                    "hook ${report.hooked}, program ${report.programHere}, remounted ${report.remounted ?: "n/a"}",
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
    }

    private fun upload(ssh: SshSession, path: String, bytes: ByteArray) {
        val r = ssh.exec("cat > $path", stdin = bytes, timeoutMs = timeoutFor(bytes.size))
        if (r.exit != 0) {
            throw IOException("Sending ${path.substringAfterLast('/')} failed: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}.")
        }
    }
}
