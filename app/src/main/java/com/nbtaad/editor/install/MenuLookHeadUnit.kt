package com.nbtaad.editor.install

import com.nbtaad.editor.addon.MenuLookKit
import com.nbtaad.editor.addon.MenuLookKits

/** Looks at, checks, applies and restores the menu look on the head unit (the steps are in [MenuLookScripts]). */
object MenuLookHeadUnit {
    /** A picture for the place [rel] under /opt/hmi/ID5/data/ro/. */
    class Picture(val rel: String, val bytes: ByteArray)

    fun run(
        host: String,
        action: MenuLookScripts.Action,
        kit: MenuLookKit?,
        brand: String,
        label: String,
        pictures: List<Picture>,
        rep: Reporter,
    ): TileScripts.Report {
        val sending = action == MenuLookScripts.Action.CHECK || action == MenuLookScripts.Action.APPLY
        if (sending) require(kit != null && kit.ok) { "The menu look files are not complete." }
        val program = if (sending) kit!!.program!! else null
        val names = pictures.mapIndexed { i, p -> MenuLookScripts.Image("img$i.png", p.rel) }
        val files = ArrayList<Pair<String, ByteArray>>()
        if (program != null) files += MenuLookKits.PROGRAM to program
        if (sending) pictures.forEachIndexed { i, p -> files += "img$i.png" to p.bytes }
        val job = MenuLookScripts.job(action, brand, label, if (sending) names else emptyList(), program?.size ?: 0)
        rep.say(
            when (action) {
                MenuLookScripts.Action.LOOK -> "Looking at the head unit…"
                MenuLookScripts.Action.CHECK -> "Checking on the head unit (nothing is changed)…"
                MenuLookScripts.Action.APPLY -> "Changing the menu on the head unit…"
                MenuLookScripts.Action.RESTORE -> "Putting the original files back…"
            },
            Level.INFO,
        )
        // making the label decodes and rebuilds the texts of every language, which the head unit does slowly
        val wait = if (sending) 360_000L else 120_000L
        val needsWrite = action == MenuLookScripts.Action.APPLY || action == MenuLookScripts.Action.RESTORE
        return HuJob.run(host, "menu look ${action.word}", files, job, needsWrite, wait, rep)
    }
}
