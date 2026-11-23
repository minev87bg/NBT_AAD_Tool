package com.nbtaad.editor.install

import com.nbtaad.editor.addon.MenuLook
import com.nbtaad.editor.addon.MenuLookKit
import com.nbtaad.editor.addon.MenuLookKits
import com.nbtaad.editor.addon.PictureMaker
import com.nbtaad.editor.addon.Pixels
import com.nbtaad.editor.net.SshSession

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

    /**
     * The picture BMW/MINI itself ships for [rel]: the factory copy kept at the first APPLY (still there as long as
     * the look stays on), or, when the look was never applied, the live file (then still the untouched original).
     * Read directly over hu-omap's network path, the way [HeadUnit]'s own diagnostics already read its folders: one
     * small file, no RAM staging needed. Null when neither is there or does not look like a PNG.
     */
    private fun fetchOriginal(ssh: SshSession, rel: String): ByteArray? {
        val backup = "/net/hu-omap/opt/aa/menu-look-backup/ro/$rel"
        val live = "/net/hu-omap/opt/hmi/ID5/data/ro/$rel"
        for (path in listOf(backup, live)) {
            val r = ssh.exec("if [ -f '$path' ]; then cat '$path'; fi", timeoutMs = 20_000, maxBytes = 2L * 1024 * 1024)
            if (r.out.size > 8 && r.out[1] == 'P'.code.toByte() && r.out[2] == 'N'.code.toByte() && r.out[3] == 'G'.code.toByte()) return r.out
        }
        return null
    }

    /**
     * The real "Main-menu tile" pictures for [brand] (not the small plain icon places), read once from the head
     * unit, with the Baidu CarLife icon they are drawn with erased so a chosen icon can be drawn over a clean
     * background instead of over Baidu's or over empty space. The places sharing one size (the three highlight
     * states of a BMW tile, say) are cleared together, by what stays the same across them
     * ([PictureMaker.withoutSubjects]: more reliable, since Baidu's icon is most likely the one thing that does), one
     * at a time only where that is not possible (one picture on its own, as MINI has). A place that cannot be read,
     * or whose icon cannot be told apart from its background at all, is simply left out: the icon is then made the
     * old way, on its own, for that place only.
     */
    fun fetchFactoryTiles(host: String, brand: String): Map<String, Pixels> {
        val read = LinkedHashMap<String, Pixels>()
        SshSession(host).use { ssh ->
            for (slot in MenuLook.slots(brand)) {
                if (!slot.what.startsWith("Main-menu tile")) continue
                val bytes = fetchOriginal(ssh, slot.rel) ?: continue
                try {
                    val px = PictureFiles.decode(bytes)
                    if (px.width == slot.width && px.height == slot.height) read[slot.rel] = px
                } catch (_: Exception) {
                    // not a usable PNG: left out, the icon is made the old way for this one place
                }
            }
        }
        val out = LinkedHashMap<String, Pixels>()
        for (group in read.entries.groupBy { it.value.width to it.value.height }.values) {
            val cleaned = PictureMaker.withoutSubjects(group.map { it.value })
            group.forEachIndexed { i, e -> out[e.key] = cleaned[i] }
        }
        return out
    }
}
