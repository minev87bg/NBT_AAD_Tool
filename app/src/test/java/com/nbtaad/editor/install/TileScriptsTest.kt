package com.nbtaad.editor.install

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs the job that goes to hu-omap's shell with this machine's own sh, on a folder made like the head unit: the same
 * script, the root and the staging folder being the two things it is given.
 */
class TileScriptsTest {
    private lateinit var root: File
    private val program = "#!/bin/sh\necho \"fake aa-tile: \$1 \$2\"\nexit 0\n".toByteArray()
    private val launcher = "#!/bin/sh\n# aa-tile-launcher.sh (nbt-aa-tile 5)\n".toByteArray()
    private val hook = ("# nbt-aa-tile hook - removed by remove-boot.sh\n" +
        "[ -f /opt/aa-tile/aa-tile-launcher.sh ] && /bin/sh /opt/aa-tile/aa-tile-launcher.sh </dev/null >/dev/null 2>&1 &\n").toByteArray()
    private val original = "#! /bin/sh\n# the head unit's own\necho starting the web server\n"

    @Before
    fun makeRoot() {
        assumeTrue(File("/bin/sh").canExecute())
        RemoteScripts.pathFix = ""
        root = File.createTempFile("nbt-aad-tile", "").also { it.delete() }
        for (d in listOf("opt/hmi/ID5/data/ro/common/widget", "opt/conn/bin", "dev/shmem", "stage")) File(root, d).mkdirs()
        File(root, "opt/hmi/ID5/data/ro/common/widget/CarLife.pbw").writeText("widget")
        File(root, "opt/conn/bin/start_webserver.sh").writeText(original)
        File(root, "opt/conn/bin/start_webserver.sh").setExecutable(true)
        File(root, "stage/aa-tile").writeBytes(program)
        File(root, "stage/aa-tile-launcher.sh").writeBytes(launcher)
        File(root, "stage/hook.txt").writeBytes(hook)
    }

    @After
    fun clean() {
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private val web get() = File(root, "opt/conn/bin/start_webserver.sh")

    private fun run(
        action: TileScripts.Action,
        mode: TileScripts.Mode = TileScripts.Mode.FOLLOW,
        programSize: Int = program.size,
    ): TileScripts.Report {
        val job = TileScripts.job(action, mode, programSize, launcher.size, hook.size, root = root.path, stage = File(root, "stage").path)
        val f = File(root, "stage/job.sh")
        f.writeText(job)
        val p = ProcessBuilder("/bin/sh", f.path).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        p.waitFor()
        return TileScripts.parse(File(root, "stage/out").takeIf { it.exists() }?.readText() ?: "NOREPORT")
    }

    @Test
    fun withoutTheCarLifeEntryNothingIsStartedOrInstalled() {
        File(root, "opt/hmi/ID5/data/ro/common/widget/CarLife.pbw").delete()
        val look = run(TileScripts.Action.STATUS)
        assertTrue(look.success)
        assertTrue(look.carlifeMissing)
        for (a in listOf(TileScripts.Action.TEST, TileScripts.Action.INSTALL)) {
            val r = run(a)
            assertFalse(r.success)
            assertEquals(TileScripts.NO_CARLIFE, r.error)
        }
        assertEquals(original, web.readText())
        assertFalse(File(root, "opt/aa-tile").exists())
        assertFalse(File(root, "dev/shmem/aa-tile").exists())
    }

    @Test
    fun withTheCarLifeEntryTheHeadUnitSaysSo() {
        val r = run(TileScripts.Action.STATUS)
        assertEquals("yes", r.state["carlife"])
        assertFalse(r.carlifeMissing)
    }

    @Test
    fun lookingChangesNothingAndSaysWhatIsThere() {
        val r = run(TileScripts.Action.STATUS)
        assertTrue(r.success)
        assertFalse(r.hooked)
        assertFalse(r.programHere)
        assertEquals("yes", r.state["webserver"])
        assertEquals(original, web.readText())
        assertFalse(File(root, "opt/aa-tile").exists())
    }

    @Test
    fun installPutsTheFilesAndTheStartLineAndKeepsTheOriginal() {
        val r = run(TileScripts.Action.INSTALL, TileScripts.Mode.ALWAYS)
        assertTrue(r.error ?: "", r.success)
        // the program is the one that was sent, and can be run
        val p = File(root, "opt/aa-tile/aa-tile")
        assertEquals(program.toList(), p.readBytes().toList())
        assertTrue(p.canExecute())
        assertEquals(launcher.toList(), File(root, "opt/aa-tile/aa-tile-launcher.sh").readBytes().toList())
        // the start file: two lines of the hook after a shebang, then the original untouched
        assertEquals("#! /bin/sh\n" + String(hook) + original, web.readText())
        assertEquals(original, File(root, "opt/aa-tile/start_webserver.sh.original").readText())
        assertTrue(web.canExecute())
        assertEquals("always", File(root, "var/opt/aa-tile/mode").readText().trim())
        // and it is seen as installed
        val s = run(TileScripts.Action.STATUS)
        assertTrue(s.hooked && s.programHere)
        assertEquals("always", s.mode)
    }

    @Test
    fun installingAgainDoesNotAddASecondStartLine() {
        run(TileScripts.Action.INSTALL)
        val first = web.readText()
        File(root, "var/opt/aa-tile/DISABLED").writeText("old")
        val r = run(TileScripts.Action.INSTALL, TileScripts.Mode.ALWAYS)
        assertTrue(r.error ?: "", r.success)
        assertEquals(first, web.readText())
        assertEquals(1, web.readText().lines().count { it.contains("nbt-aa-tile hook") })
        assertEquals("always", File(root, "var/opt/aa-tile/mode").readText().trim())
        // an earlier switch-off is cleared by a new install, as the author's script does
        assertFalse(File(root, "var/opt/aa-tile/DISABLED").exists())
    }

    @Test
    fun anOldSavedOriginalIsNotUsedToBuildTheNewStartFile() {
        File(root, "opt/aa-tile").mkdirs()
        File(root, "opt/aa-tile/start_webserver.sh.original").writeText("#! /bin/sh\necho an older software version\n")
        val r = run(TileScripts.Action.INSTALL)
        assertTrue(r.error ?: "", r.success)
        assertTrue(web.readText().endsWith(original))
        assertFalse(web.readText().contains("older software"))
    }

    @Test
    fun removeGivesTheOriginalBackByteForByteAndTakesTheFilesAway() {
        run(TileScripts.Action.INSTALL)
        val r = run(TileScripts.Action.REMOVE)
        assertTrue(r.error ?: "", r.success)
        assertEquals(original, web.readText())
        assertFalse(File(root, "opt/aa-tile/aa-tile").exists())
        assertFalse(File(root, "opt/aa-tile/aa-tile-launcher.sh").exists())
        assertFalse(File(root, "var/opt/aa-tile").exists())
        assertFalse(run(TileScripts.Action.STATUS).hooked)
    }

    @Test
    fun removeDoesNotTouchTheStartFileWhenTheSavedOriginalIsMissing() {
        run(TileScripts.Action.INSTALL)
        File(root, "opt/aa-tile/start_webserver.sh.original").delete()
        val before = web.readText()
        val r = run(TileScripts.Action.REMOVE)
        assertNotNull(r.error)
        assertEquals(before, web.readText())
    }

    @Test
    fun removeWithNothingInstalledIsHarmless() {
        val r = run(TileScripts.Action.REMOVE)
        assertTrue(r.error ?: "", r.success)
        assertEquals(original, web.readText())
    }

    @Test
    fun aFileThatArrivedWithTheWrongSizeChangesNothing() {
        val r = run(TileScripts.Action.INSTALL, programSize = program.size + 1)
        assertNotNull(r.error)
        assertFalse(r.success)
        assertEquals(original, web.readText())
        assertFalse(File(root, "opt/aa-tile/aa-tile").exists())
    }

    @Test
    fun theOlderCasellaBuildIsNotBuiltOver() {
        web.writeText(original + "# casella-aa hook\n")
        val before = web.readText()
        val r = run(TileScripts.Action.INSTALL)
        assertTrue(r.casella)
        assertNotNull(r.error)
        assertEquals(before, web.readText())
        assertFalse(File(root, "opt/aa-tile").exists())
    }

    @Test
    fun aShellThatIsNotHuOmapsChangesNothing() {
        File(root, "opt/hmi").deleteRecursively()
        val r = run(TileScripts.Action.INSTALL)
        assertNotNull(r.error)
        assertEquals(original, web.readText())
    }

    @Test
    fun aStartFileThatIsNotThereIsDifferentSoftware() {
        web.delete()
        val r = run(TileScripts.Action.INSTALL)
        assertNotNull(r.error)
        assertFalse(File(root, "opt/aa-tile/aa-tile").exists())
    }

    @Test
    fun theTestRunsTheProgramFromRamAndLeavesNothingBehind() {
        val r = run(TileScripts.Action.TEST)
        assertTrue(r.error ?: "", r.success)
        assertEquals(0, r.rc)
        assertTrue(r.log, r.log.contains("fake aa-tile: follow ${TileScripts.TEST_SECONDS}"))
        assertEquals(original, web.readText())
        assertFalse(File(root, "opt/aa-tile").exists())
        assertEquals(emptyList<String>(), File(root, "dev/shmem").list()!!.toList())
    }

    @Test
    fun aTestThatEndsWithAnErrorCodeReportsIt() {
        File(root, "stage/aa-tile").writeText("#!/bin/sh\necho crash\nexit 9\n")
        val r = run(TileScripts.Action.TEST, programSize = "#!/bin/sh\necho crash\nexit 9\n".length)
        assertTrue(r.success)
        assertEquals(9, r.rc)
        assertTrue(TileScripts.exitMeaning(r.rc).contains("crashed"))
    }

    @Test
    fun theReportIsReadLineByLine() {
        val r = TileScripts.parse(
            "REMOUNT ok\nJOB install\nST hook yes\nST last started mode=follow counter=1\nOK done\nINFO stopped\nWARN careful\n" +
                "LOG-BEGIN\nST fake yes\nERR in the log\nLOG-END\nRC 2\nEND 0\n",
        )
        assertEquals(true, r.remounted)
        assertTrue(r.hooked)
        assertEquals("started mode=follow counter=1", r.last)
        assertEquals(listOf("done"), r.ok)
        assertEquals(listOf("stopped"), r.info)
        assertEquals(listOf("careful"), r.warn)
        assertEquals(2, r.rc)
        assertNull(r.error)
        assertTrue(r.finished)
        assertTrue(r.log.contains("ERR in the log"))
        assertFalse(r.state.containsKey("fake"))
        val none = TileScripts.parse("REMOUNT failed\nNOREPORT\n")
        assertTrue(none.noReport)
        assertEquals(false, none.remounted)
        assertFalse(none.success)
        val failed = TileScripts.parse("ERR no good\nEND 1\n")
        assertEquals("no good", failed.error)
        assertFalse(failed.success)
    }

    @Test
    fun theLoginSideScriptsUseHuOmapsRamAndOnlyRemountWhenAsked() {
        RemoteScripts.pathFix = ""
        val prep = TileScripts.prepare()
        assertTrue(prep.contains("/net/hu-omap/dev/shmem"))
        assertTrue(prep.contains(TileScripts.STAGE_DIR))
        val ram = "/net/hu-omap/dev/shmem/${TileScripts.STAGE_DIR}"
        val look = TileScripts.start(ram, remount = false)
        assertTrue(look.contains("on -f /net/hu-omap/ /bin/sh /dev/shmem/${TileScripts.STAGE_DIR}/job.sh"))
        assertFalse(look.contains("remount"))
        assertTrue(TileScripts.start(ram, remount = true).contains("mount -o remount rw"))
        // on the processor that is hu-omap itself there is no hop
        val local = TileScripts.start("/dev/shmem/${TileScripts.STAGE_DIR}", remount = false)
        assertFalse(local.contains("on -f"))
        assertTrue(local.contains("/bin/sh /dev/shmem/${TileScripts.STAGE_DIR}/job.sh"))
        // the job is told where its folder is, which is where the login found room
        val onDisk = TileScripts.start("/net/hu-omap/fs/ram5/${TileScripts.STAGE_DIR}", remount = false, there = "/fs/ram5/${TileScripts.STAGE_DIR}")
        assertTrue(onDisk.contains("on -f /net/hu-omap/ /bin/sh /fs/ram5/${TileScripts.STAGE_DIR}/job.sh /fs/ram5/${TileScripts.STAGE_DIR}"))
    }

    // ---- the login's search for room in hu-omap's RAM ----

    private class Places(val hop: File, val own: File)

    private fun places(shmem: Boolean = true, ram5: Boolean = true, hopExists: Boolean = true): Places {
        val hop = File(root, "net/hu-omap")
        if (hopExists) hop.mkdirs()
        if (hopExists && shmem) File(hop, "dev/shmem").mkdirs()
        if (hopExists && ram5) File(hop, "fs/ram5").mkdirs()
        val own = File(root, "own-shmem").also { it.mkdirs() }
        return Places(hop, own)
    }

    private fun prepareOn(p: Places): String {
        val proc = ProcessBuilder("/bin/sh", "-c", TileScripts.prepare(p.hop.path, p.own.path)).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }

    private val stage = TileScripts.STAGE_DIR

    @Test
    fun theStagingFolderGoesIntoSharedMemoryWhenThatWorks() {
        val p = places()
        val out = prepareOn(p)
        assertTrue(out, out.contains("RAM ${p.hop.path}/dev/shmem/$stage /dev/shmem/$stage"))
        assertTrue(File(p.hop, "dev/shmem/$stage").isDirectory)
        assertFalse(out.contains("TRY"))
    }

    @Test
    fun whenSharedMemoryRefusesTheFolderTheRamDiskIsUsedAndTheLogSaysWhy() {
        val p = places()
        // a file of that name where the folder should be made: mkdir fails the way a refusing file system would
        File(p.hop, "dev/shmem/$stage").writeText("in the way")
        val out = prepareOn(p)
        assertTrue(out, out.contains("RAM ${p.hop.path}/fs/ram5/$stage /fs/ram5/$stage"))
        assertTrue(out, out.contains("TRY ${p.hop.path}/dev/shmem: no folder could be made"))
        assertTrue(File(p.hop, "fs/ram5/$stage").isDirectory)
    }

    @Test
    fun anOldFolderIsEmptiedAndAMissingPlaceIsToldAsATry() {
        val p = places(shmem = false)
        File(p.hop, "fs/ram5/$stage").mkdirs()
        File(p.hop, "fs/ram5/$stage/old-file").writeText("left over")
        val out = prepareOn(p)
        assertTrue(out, out.contains("TRY ${p.hop.path}/dev/shmem: not a folder"))
        assertTrue(out, out.contains("RAM ${p.hop.path}/fs/ram5/$stage /fs/ram5/$stage"))
        assertFalse(File(p.hop, "fs/ram5/$stage/old-file").exists())
    }

    @Test
    fun withoutAnyPlaceTheAnswerIsNoRamWithWhatWasTried() {
        val p = places(shmem = false, ram5 = false)
        val out = prepareOn(p)
        assertTrue(out, out.lines().contains("NORAM"))
        assertTrue(out, out.contains("TRY ${p.hop.path}/dev/shmem"))
        assertTrue(out, out.contains("TRY ${p.hop.path}/fs/ram5"))
        assertFalse(out.contains("RAM ${p.own.path}"))
    }

    @Test
    fun onTheProcessorThatIsHuOmapItselfItsOwnSharedMemoryIsUsed() {
        val p = places(hopExists = false)
        val out = prepareOn(p)
        assertTrue(out, out.contains("RAM ${p.own.path}/$stage ${p.own.path}/$stage"))
        assertTrue(File(p.own, stage).isDirectory)
    }

    @Test
    fun theJobUsesTheFolderItIsToldAndOtherwiseItsOwn() {
        val job = TileScripts.job(TileScripts.Action.STATUS, TileScripts.Mode.FOLLOW, program.size, launcher.size, hook.size, root = root.path)
        val f = File(root, "stage/job.sh")
        f.writeText(job)
        val p = ProcessBuilder("/bin/sh", f.path, File(root, "stage").path).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        p.waitFor()
        val r = TileScripts.parse(File(root, "stage/out").takeIf { it.exists() }?.readText() ?: "NOREPORT")
        assertTrue(r.finished)
        assertTrue(job.contains("S=\${1:-${TileScripts.stageThere()}}"))
    }
}
