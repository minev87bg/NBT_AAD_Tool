package com.nbtaad.editor.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Which install the folders of a head unit describe: the one in /opt/aa, NBTApps', or none this app knows. */
class LayoutChoiceTest {
    private val id = RemoteScripts.NBTAPPS_ID
    private val plugin = "/net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad"
    private val aaDir = "/net/hu-omap/fs/sda0/opt/aa"
    private val na = "/net/hu-omap/var/opt/nbtapp/apps/$id"
    private val nd = "/net/hu-omap/var/opt/nbtapp/data/$id"

    private fun out(vararg lines: String) = lines.joinToString("\n") + "\n"

    @Test
    fun aLeftOverOptAaFolderDoesNotHideAnNbtAppsInstall() {
        // what an uninstall leaves (the folder, no daemon) next to a fresh NBTApps install: the case from the car
        val o = out("P ", "P /net/hu-omap/fs/sda0", "AA $aaDir", "PL $plugin", "P /net/hu-omap", "NA $na", "ND $nd")
        val lay = HeadUnit.layoutOf(o)!!
        assertTrue(lay.nbtApps)
        assertEquals(na, lay.aa)
        assertEquals(nd, lay.env)
        assertEquals(plugin, lay.plugin)
    }

    @Test
    fun aDaemonInOptAaIsAHandInstallEvenWithNbtAppsFoldersAround() {
        val o = out("P /net/hu-omap/fs/sda0", "AA $aaDir", "AD $aaDir", "PL $plugin", "P /net/hu-omap", "NA $na", "ND $nd")
        val lay = HeadUnit.layoutOf(o)!!
        assertFalse(lay.nbtApps)
        assertEquals(aaDir, lay.aa)
        assertTrue(lay.daemonHere)
    }

    @Test
    fun anEmptyOptAaFolderAloneIsStillTheFolderToInstallInto() {
        val lay = HeadUnit.layoutOf(out("P /net/hu-omap/fs/sda0", "AA $aaDir", "PL $plugin"))!!
        assertFalse(lay.nbtApps)
        assertFalse(lay.daemonHere)
        assertEquals(aaDir, lay.aa)
    }

    @Test
    fun nbtAppsAloneAndNothingAtAll() {
        assertTrue(HeadUnit.layoutOf(out("P /net/hu-omap", "NA $na"))!!.nbtApps)
        assertNull(HeadUnit.layoutOf(out("P ", "P /net/hu-omap/fs/sda0", "PL $plugin")))
    }

    @Test
    fun theDaemonMustBeInTheFolderThatWasPicked() {
        // an nbt-aad in the /opt/aa of another prefix is not the one of the folder that was chosen
        val loc = RemoteScripts.parseLocated(out("P ", "AA /opt/aa", "P /net/hu-omap/fs/sda0", "AA $aaDir", "AD $aaDir", "PL $plugin"))
        assertTrue(loc.aaDaemon)
        val other = RemoteScripts.parseLocated(out("P ", "AA /opt/aa", "AD /opt/aa", "P /net/hu-omap/fs/sda0", "AA $aaDir", "PL $plugin"))
        assertEquals(aaDir, other.aa)
        assertFalse(other.aaDaemon)
    }

    @Test
    fun theFirstPackageOfAPrefixWinsAndAnotherIdIsFoundToo() {
        val loc = RemoteScripts.parseLocated(
            out("P /net/hu-omap", "NA $na", "ND $nd", "NA /net/hu-omap/var/opt/nbtapp/apps/other", "ND /net/hu-omap/var/opt/nbtapp/data/other")
        )
        assertEquals(na, loc.nbtApp)
        assertEquals(nd, loc.nbtData)
        val other = RemoteScripts.parseLocated(out("P /net/hu-omap", "NA /net/hu-omap/var/opt/nbtapp/apps/some.other.id"))
        assertEquals("/net/hu-omap/var/opt/nbtapp/apps/some.other.id", other.nbtApp)
    }

    @Test
    fun theLocateScriptFindsADaemonInAnyNbtAppsPackageAndInOptAa() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = File.createTempFile("nbt-aad-locate", "").also { it.delete() }
        try {
            File(root, "opt/aa").mkdirs()
            File(root, "opt/hmi/ID5/data/ro/Plugins/NbtAad").mkdirs()
            File(root, "var/opt/nbtapp/apps/some.other.id").mkdirs()
            File(root, "var/opt/nbtapp/apps/some.other.id/nbt-aad").writeText("d")
            File(root, "var/opt/nbtapp/apps/empty.package").mkdirs()
            File(root, "var/opt/nbtapp/data/some.other.id").mkdirs()
            RemoteScripts.pathFix = ""
            fun locate(): RemoteScripts.Located {
                val script = RemoteScripts.locate().replace("for p in \"\" /net/hu-omap/fs/sda0 /net/hu-omap; do", "for p in \"${root.path}\"; do")
                val p = ProcessBuilder("/bin/sh", "-c", script).redirectError(File("/dev/null")).start()
                val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
                p.waitFor()
                return RemoteScripts.parseLocated(text)
            }
            // an empty /opt/aa, a plugin and a package of NBTApps that holds a daemon
            var loc = locate()
            assertEquals(File(root, "opt/aa").path, loc.aa)
            assertFalse(loc.aaDaemon)
            assertEquals(File(root, "var/opt/nbtapp/apps/some.other.id").path, loc.nbtApp)
            assertEquals(File(root, "var/opt/nbtapp/data/some.other.id").path, loc.nbtData)
            // a package without a daemon is not an install
            File(root, "var/opt/nbtapp/apps/some.other.id/nbt-aad").delete()
            assertNull(locate().nbtApp)
            // the daemon in /opt/aa
            File(root, "opt/aa/nbt-aad").writeText("d")
            assertTrue(locate().aaDaemon)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foldersAreTheSameWhateverTheWayToTheFileSystem() {
        assertTrue(RemoteScripts.sameFolder("/opt/aa", "/net/hu-omap/fs/sda0/opt/aa"))
        assertTrue(RemoteScripts.sameFolder("/fs/sda0/opt/aa/", "/opt/aa"))
        assertTrue(RemoteScripts.sameFolder("/var/opt/nbtapp/apps/x", "/net/hu-omap/var/opt/nbtapp/apps/x"))
        assertFalse(RemoteScripts.sameFolder("/opt/aa", "/var/opt/nbtapp/apps/x"))
        assertFalse(RemoteScripts.sameFolder("/opt/aa", "/opt/aa2"))
    }

    @Test
    fun aDaemonThatRunsFromNbtAppsFolderIsNbtAppsToo() {
        val f = RemoteScripts.ForeignInstall(plugin, "/var/opt/nbtapp/apps/$id/nbt-aad", "/net/hu-omap/var/opt/nbtapp/apps/$id/nbt-aad")
        assertTrue(f.byNbtApps)
        assertEquals(HeadUnit.NBTAPPS_NOTE, HeadUnit.unfamiliarNote(f))
        assertFalse(RemoteScripts.ForeignInstall(plugin, "/opt/x/nbt-aad", "/opt/x/nbt-aad").byNbtApps)
    }

    @Test
    fun aPluginWithoutADaemonNextToItIsNotMovedByAnUninstall() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = File.createTempFile("nbt-aad-own", "").also { it.delete() }
        try {
            val aa = File(root, "opt/aa").also { it.mkdirs() }
            val pl = File(root, "opt/hmi/ID5/data/ro/Plugins/NbtAad").also { it.mkdirs() }
            File(pl, "libNbtAad.so").writeText("somebody else's plugin")
            File(aa, "NbtAad.old").mkdirs()
            RemoteScripts.pathFix = ""
            fun run(apply: Boolean): RemoteScripts.UninstallPlan {
                val p = ProcessBuilder("/bin/sh", "-c", RemoteScripts.uninstall(aa.path, pl.path, null, keepEnv = false, apply = apply))
                    .redirectError(File("/dev/null")).start()
                val t = p.inputStream.readBytes().toString(Charsets.UTF_8)
                p.waitFor()
                return RemoteScripts.parseUninstall(t)
            }
            val plan = run(false)
            assertTrue(plan.moves.isEmpty())
            assertTrue(plan.nothing)
            run(true)
            assertEquals("somebody else's plugin", File(pl, "libNbtAad.so").readText())
            assertTrue(File(aa, "NbtAad.old").isDirectory)
            // with the daemon there, the plugin is theirs to move
            File(aa, "nbt-aad").writeText("d")
            assertEquals(1, run(false).moves.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
