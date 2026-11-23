package com.nbtaad.editor.install

import com.nbtaad.editor.addon.CleanedTile
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

    /** Reads [path] over hu-omap's network path if it is there, the way [HeadUnit]'s own diagnostics already read
     *  its folders: one small file, no RAM staging needed. Null when it is not there or does not look like a PNG. */
    private fun catPng(ssh: SshSession, path: String): ByteArray? {
        val r = ssh.exec("if [ -f '$path' ]; then cat '$path'; fi", timeoutMs = 20_000, maxBytes = 2L * 1024 * 1024)
        return r.out.takeIf { it.size > 8 && it[1] == 'P'.code.toByte() && it[2] == 'N'.code.toByte() && it[3] == 'G'.code.toByte() }
    }

    /**
     * The picture BMW/MINI itself ships for [rel]: the factory copy kept at the first APPLY (still there as long as
     * the look stays on), or, when the look was never applied, the live file (then still the untouched original).
     */
    private fun fetchOriginal(ssh: SshSession, rel: String): ByteArray? =
        catPng(ssh, "/net/hu-omap/opt/aa/menu-look-backup/ro/$rel") ?: catPng(ssh, "/net/hu-omap/opt/hmi/ID5/data/ro/$rel")

    /** The real picture for one place, as read from the head unit: [raw] untouched (for saving and looking at
     *  closely), [cleaned] with its icon erased (for drawing a chosen one over instead). */
    class FactoryTile(val raw: ByteArray, val cleaned: CleanedTile)

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
    fun fetchFactoryTiles(host: String, brand: String): Map<String, FactoryTile> {
        val read = LinkedHashMap<String, Pair<ByteArray, Pixels>>()
        SshSession(host).use { ssh ->
            for (slot in MenuLook.slots(brand)) {
                if (!slot.what.startsWith("Main-menu tile")) continue
                val bytes = fetchOriginal(ssh, slot.rel) ?: continue
                try {
                    val px = PictureFiles.decode(bytes)
                    if (px.width == slot.width && px.height == slot.height) read[slot.rel] = bytes to px
                } catch (_: Exception) {
                    // not a usable PNG: left out, the icon is made the old way for this one place
                }
            }
        }
        val out = LinkedHashMap<String, FactoryTile>()
        for (group in read.entries.groupBy { it.value.second.width to it.value.second.height }.values) {
            val cleaned = PictureMaker.withoutSubjects(group.map { it.value.second })
            group.forEachIndexed { i, e -> out[e.key] = FactoryTile(e.value.first, cleaned[i]) }
        }
        return out
    }

    /**
     * The picture the head unit is showing right now for every place of [brand] (the live file, never the factory
     * backup [fetchOriginal] prefers): once the look is already applied, this is the person's own icon as it is on
     * the car's display, so pressing LOOK can offer it back instead of asking for it to be chosen or made again just
     * to reapply it unchanged. A place that cannot be read, or is not there, is simply left out.
     */
    fun fetchCurrentPictures(host: String, brand: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        SshSession(host).use { ssh ->
            for (slot in MenuLook.slots(brand)) {
                catPng(ssh, "/net/hu-omap/opt/hmi/ID5/data/ro/${slot.rel}")?.let { out[slot.rel] = it }
            }
        }
        return out
    }
}
