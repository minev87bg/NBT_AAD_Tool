package com.nbtaad.editor.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A login on the head unit's other computer (hu-jacinto, the address that answers first on the car this was found on) does
 * not see hu-omap's /var, where NBTApps keeps its program and settings. hu-omap is asked itself, the way NBTApps' guide
 * does it, and what it answers is read from a file in its RAM.
 */
class HopToHuOmapTest {
    private val id = RemoteScripts.NBTAPPS_ID
    private val plugin = "/net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad"
    private val aaDir = "/net/hu-omap/fs/sda0/opt/aa"

    private fun out(vararg lines: String) = lines.joinToString("\n") + "\n"

    /** What the car's first address printed when hu-omap answered (the log of 2026-11-21 plus the hop's lines). */
    private val carWithHop = out(
        "P ", "P /net/hu-omap/fs/sda0", "AA $aaDir", "PL $plugin", "P /net/hu-omap",
        "HOP ok", "HNA /var/opt/nbtapp/apps/$id", "HND /var/opt/nbtapp/data/$id",
    )

    @Test
    fun whatHuOmapReportsMakesAnNbtAppsLayoutThatIsNotReachableFromThisLogin() {
        val lay = HeadUnit.layoutOf(carWithHop)!!
        assertTrue(lay.nbtApps)
        assertTrue(lay.viaHop)
        assertEquals("/var/opt/nbtapp/apps/$id", lay.aa)
        assertEquals("/var/opt/nbtapp/data/$id", lay.env)
        // the plugin folder is on the disk both computers share, so it is still found where this login sees it
        assertEquals(plugin, lay.plugin)
        assertTrue(RemoteScripts.hopAnswered(carWithHop))
    }

    @Test
    fun withoutAnswerOrWithAnEmptyOneTheLeftOverFolderIsAsBefore() {
        val none = out("P ", "P /net/hu-omap/fs/sda0", "AA $aaDir", "PL $plugin", "P /net/hu-omap")
        val lay = HeadUnit.layoutOf(none)!!
        assertFalse(lay.nbtApps)
        assertFalse(lay.viaHop)
        assertFalse(RemoteScripts.hopAnswered(none))
        // hu-omap answered and has no package with a daemon
        val empty = HeadUnit.layoutOf(none + "HOP ok\n")!!
        assertFalse(empty.nbtApps)
    }

    @Test
    fun whatThisLoginSeesItselfComesFirst() {
        val own = out("P ", "AA /opt/aa", "NA /var/opt/nbtapp/apps/$id", "ND /var/opt/nbtapp/data/$id", "HOP ok", "HNA /var/opt/nbtapp/apps/other.id")
        val lay = HeadUnit.layoutOf(own)!!
        assertTrue(lay.nbtApps)
        assertFalse(lay.viaHop)
        assertEquals("/var/opt/nbtapp/apps/$id", lay.aa)
    }

    @Test
    fun thePackageOfThisAppsKindComesFirstAndItsDataFolderGoesWithIt() {
        val o = out(
            "P /net/hu-omap", "HOP ok",
            "HNA /var/opt/nbtapp/apps/a.first", "HND /var/opt/nbtapp/data/a.first",
            "HNA /var/opt/nbtapp/apps/$id", "HND /var/opt/nbtapp/data/$id",
        )
        assertEquals("/var/opt/nbtapp/apps/$id" to "/var/opt/nbtapp/data/$id", RemoteScripts.parseHop(o))
        // a package without a data folder does not take the next one's
        val p = out("HNA /var/opt/nbtapp/apps/x", "HNA /var/opt/nbtapp/apps/y", "HND /var/opt/nbtapp/data/y")
        assertEquals("/var/opt/nbtapp/apps/x" to null, RemoteScripts.parseHop(p))
        assertNull(RemoteScripts.parseHop(out("HOP ok")))
    }

    @Test
    fun theStatusSaysThatTheFoldersWereNotSeen() {
        val s = RemoteScripts.parseStatus("/var/opt/nbtapp/apps/$id", plugin, out("L NbtAad.xml 16113", "BUILD-BEGIN", "  Build: v1.9.116 (Nov 20 2026 10:54:43)", "BUILD-END"), nbtApps = true, otherNode = true)
        assertTrue(s.otherNode)
        assertFalse(s.hasDaemon)
        assertEquals("v1.9.116", s.installedVersion)
        assertTrue(s.versionFromLog)
        assertFalse(s.envMissing)
        assertTrue(s.describe().contains("not seen from this login"))
        assertTrue(s.withDaemonFile(null).otherNode)
    }

    // ---- the scripts, run on fake trees with a stand-in for `on` ----

    private class Fake(val root: File) {
        val local = File(root, "jacinto/var/opt/nbtapp")      // this login's own /var/opt/nbtapp (not there)
        val remote = File(root, "omap/var/opt/nbtapp")        // hu-omap's
        val ram = File(root, "omap/dev/shmem").also { it.mkdirs() }

        fun run(prelude: String, vars: String, on: String, withLocalNbt: Boolean = false): String {
            if (withLocalNbt) local.mkdirs()
            val part = RemoteScripts.hopPart(on, local.path, remote.path, ram.path, ram.path).replace('¤', '$')
            val script = prelude + "\n" + vars + "\n" + part + "\nexit 0\n"
            val p = ProcessBuilder("/bin/sh", "-c", script).redirectError(File("/dev/null")).start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            return text
        }
    }

    private val fakeOn = "fakeon() { /bin/sh -c \"\$3\"; }"

    private fun withRoot(body: (Fake) -> Unit) {
        assumeTrue(File("/bin/sh").canExecute())
        val root = File.createTempFile("nbt-aad-hop", "").also { it.delete() }
        try { body(Fake(root)) } finally { root.deleteRecursively() }
    }

    @Test
    fun huOmapIsAskedAndTheAnswerComesBackAndTheRamFileIsGone() = withRoot { f ->
        File(f.remote, "apps/$id").mkdirs()
        File(f.remote, "apps/$id/nbt-aad").writeText("d")
        File(f.remote, "data/$id").mkdirs()
        File(f.remote, "apps/empty.package").mkdirs()
        val text = f.run(fakeOn, "loc_ad=\nloc_na=", "fakeon")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("HOP ok", "HNA ${f.remote.path}/apps/$id", "HND ${f.remote.path}/data/$id"), lines)
        assertEquals(0, f.ram.listFiles()!!.size)
        val loc = RemoteScripts.parseLocated(text)
        assertTrue(loc.viaHop)
        assertEquals("${f.remote.path}/apps/$id", loc.nbtApp)
    }

    @Test
    fun aPackageWithoutADaemonIsNotReportedButHuOmapAnswered() = withRoot { f ->
        File(f.remote, "apps/$id").mkdirs()
        val text = f.run(fakeOn, "loc_ad=\nloc_na=", "fakeon")
        assertEquals(listOf("HOP ok"), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        assertNull(RemoteScripts.parseLocated(text).nbtApp)
    }

    @Test
    fun huOmapIsNotAskedWhenADaemonOrNbtAppsIsAlreadyKnownOrTheFoldersAreHere() = withRoot { f ->
        File(f.remote, "apps/$id").mkdirs()
        File(f.remote, "apps/$id/nbt-aad").writeText("d")
        assertEquals("", f.run(fakeOn, "loc_ad=1\nloc_na=", "fakeon").trim())
        assertEquals("", f.run(fakeOn, "loc_ad=\nloc_na=1", "fakeon").trim())
        // this login has /var/opt/nbtapp itself (it is hu-omap): nothing to ask
        assertEquals("", f.run(fakeOn, "loc_ad=\nloc_na=", "fakeon", withLocalNbt = true).trim())
    }

    @Test
    fun anAnswerThatNeverComesLeavesNothingAndNoFile() = withRoot { f ->
        // `on` is not there, or fails: the lookup goes on as it did before
        val text = f.run("", "loc_ad=\nloc_na=", "false")
        assertEquals("", text.trim())
        assertEquals(0, f.ram.listFiles()!!.size)
    }

    @Test
    fun theScriptsAreValidShellAndTheHopIsInTheLookup() {
        assumeTrue(File("/bin/sh").canExecute())
        assertTrue(RemoteScripts.locate().contains("nbt-aad-tool.hop"))
        assertTrue(RemoteScripts.probe().contains("on -f /net/hu-omap/ /bin/sh -c"))
        for ((name, s) in listOf("locate" to RemoteScripts.locate(), "probe" to RemoteScripts.probe(), "deepLook" to RemoteScripts.deepLook())) {
            val p = ProcessBuilder("/bin/sh", "-n", "-c", s).redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("$name: $text", 0, p.waitFor())
        }
    }

    @Test
    fun theDeepLookOnlyReadsAndKeepsItsRamFileShortLived() {
        val s = RemoteScripts.deepLook()
        // read-only listings, and one small file in hu-omap's RAM that is removed again
        assertFalse(Regex("""\b(mv|cp|mkdir|chmod|mount)\b""").containsMatchIn(s))
        assertEquals(2, Regex("rm -f /net/hu-omap/dev/shmem/nbt-aad-tool.deep").findAll(s).count())
        assertNotNull(Regex("nbt-aad-tool.deep").find(s))
    }
}
