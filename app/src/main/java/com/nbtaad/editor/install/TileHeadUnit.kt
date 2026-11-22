package com.nbtaad.editor.install

import com.nbtaad.editor.addon.TileKit
import com.nbtaad.editor.addon.TileKits

/**
 * Looks at, tests, installs and removes nbt-aa-tile on the head unit (the steps themselves are in [TileScripts]).
 * What is sent goes into hu-omap's RAM first; the only thing written to the flash is by an install, and it is the
 * three files the author's own install script writes plus the start line in start_webserver.sh.
 */
object TileHeadUnit {
    /**
     * Runs [action] on the head unit at [host]. [kit] is needed for a test and an install (its files are sent first).
     * Returns what the head unit reported; a report that says the job stopped (`error`) is not an exception, so that the
     * caller can show what the head unit still has.
     */
    fun run(host: String, action: TileScripts.Action, kit: TileKit?, mode: TileScripts.Mode, rep: Reporter): TileScripts.Report {
        val sending = action == TileScripts.Action.TEST || action == TileScripts.Action.INSTALL
        if (sending) require(kit != null && kit.ok) { "The tile files are not complete." }
        val files = if (sending) listOf(
            TileKits.PROGRAM to kit!!.program, TileKits.LAUNCHER to kit.launcher, TileKits.HOOK to kit.hook,
        ) else emptyList()
        val job = TileScripts.job(
            action, mode,
            programSize = kit?.program?.size ?: 0,
            launcherSize = kit?.launcher?.size ?: 0,
            hookSize = kit?.hook?.size ?: 0,
        )
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
        return HuJob.run(host, "tile ${action.word}", files, job, needsWrite, wait, rep)
    }
}
