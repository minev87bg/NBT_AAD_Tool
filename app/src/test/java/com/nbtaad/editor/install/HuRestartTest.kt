package com.nbtaad.editor.install


import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The restart job, run with this machine's sh on a folder made like the head unit, with a stand-in for `shutdown`. */
class HuRestartTest {
    private lateinit var root: File

    @Before
    fun makeRoot() {
        assumeTrue(File("/bin/sh").canExecute())
        RemoteScripts.pathFix = ""
        root = File.createTempFile("nbt-aad-restart", "").also { it.delete() }
        File(root, "stage").mkdirs()
    }

    @After
    fun clean() {
        RemoteScripts.pathFix = RemoteScripts.PATH_FIX
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private fun run(wide: Boolean = false): TileScripts.Report {
        val f = File(root, "stage/job.sh")
        f.writeText(HuRestart.job(root = root.path, stage = File(root, "stage").path, delaySeconds = 0, wide = wide))
        ProcessBuilder("/bin/sh", f.path).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start().waitFor()
        return TileScripts.parse(File(root, "stage/out").takeIf { it.exists() }?.readText() ?: "NOREPORT")
    }

    @Test
    fun aHeadUnitWithShutdownIsAskedToRestartWithTheRebootOption() {
        File(root, "sbin").mkdirs()
        val cmd = File(root, "sbin/shutdown")
        cmd.writeText("#!/bin/sh\necho \"\$@\" > ${root.path}/asked\n")
        cmd.setExecutable(true)
        val r = run()
        assertTrue(r.error ?: "", r.success)
        assertTrue(r.ok.toString(), r.ok.any { it.contains("asked to restart") })
        val asked = File(root, "asked")
        val end = System.currentTimeMillis() + 5000
        while (!asked.exists() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("shutdown was not run", asked.exists())
        assertEquals("-b", asked.readText().trim())
        assertEquals("full", r.state["kind"])
    }

    private fun fake(name: String, marker: String) {
        File(root, "sbin").mkdirs()
        val f = File(root, "sbin/$name")
        f.writeText("#!/bin/sh\necho \"\$@\" > ${root.path}/$marker\n")
        f.setExecutable(true)
    }

    private fun waitFor(f: File): Boolean {
        val end = System.currentTimeMillis() + 5000
        while (!f.exists() && System.currentTimeMillis() < end) Thread.sleep(50)
        return f.exists()
    }

    @Test
    fun onOffDSICommanderIsReportedButNeverRunEvenWithoutShutdown() {
        // it has locked up a real head unit's screen and SSH together: the job only ever looks for it, never runs it
        fake("OnOffDSICommander", "app-asked")
        val r = run()
        assertTrue(r.finished)
        assertFalse(r.success)
        assertEquals(HuRestart.NO_COMMAND, r.error)
        assertTrue(r.info.toString(), r.info.any { it.contains("OnOffDSICommander") })
        Thread.sleep(300)
        assertFalse("OnOffDSICommander must never be run", File(root, "app-asked").exists())
    }

    @Test
    fun whenBothAreThereTheWholeHeadUnitIsRestartedAndTheHmiIsLeftAlone() {
        fake("shutdown", "asked")
        fake("OnOffDSICommander", "app-asked")
        val r = run()
        assertEquals("full", r.state["kind"])
        assertTrue(waitFor(File(root, "asked")))
        Thread.sleep(300)
        assertFalse(File(root, "app-asked").exists())
    }

    @Test
    fun aHeadUnitWithoutShutdownSaysSoAndTheyAreToldToHoldThePowerButton() {
        val r = run()
        assertTrue(r.finished)
        assertFalse(r.success)
        assertEquals(HuRestart.NO_COMMAND, r.error)
        assertTrue(HuRestart.NO_COMMAND.contains("power button"))
        assertTrue(r.state["kind"] == null)
    }

    @Test
    fun sysshutdownIsRecognisedTheSameWayAsShutdown() {
        fake("sysshutdown", "asked")
        val r = run()
        assertTrue(r.error ?: "", r.success)
        assertEquals("full", r.state["kind"])
        assertTrue(waitFor(File(root, "asked")))
        assertEquals("-b", File(root, "asked").readText().trim())
    }

    @Test
    fun theHeadUnitsSoftwareVersionIsReadIntoTheReportWhenItIsThere() {
        fake("shutdown", "asked")
        File(root, "opt/sys/etc").mkdirs()
        File(root, "opt/sys/etc/nbt_version.txt").writeText("NBT-EVO 03.18 (2018-03)\nsome other line that is not read\n")
        val r = run()
        assertTrue(r.error ?: "", r.success)
        assertTrue(r.info.toString(), r.info.any { it.contains("NBT-EVO 03.18 (2018-03)") })
    }

    @Test
    fun aWiderSearchReachesPastTheNormalDepthLimitButCostsMore() {
        // the plugin's own program, so the search's self-test passes
        File(root, "opt/aa").mkdirs()
        File(root, "opt/aa/nbt-aad").writeText("x")
        // a shutdown command buried well past the normal search's depth limit, and not in any of the usual places
        var deep = File(root, "deep")
        repeat(10) { deep = File(deep, "d") }
        deep.mkdirs()
        val cmd = File(deep, "shutdown")
        cmd.writeText("#!/bin/sh\necho \"\$@\" > ${root.path}/asked\n")
        cmd.setExecutable(true)

        val narrow = run(wide = false)
        assertFalse(narrow.success)
        assertEquals(HuRestart.NO_COMMAND, narrow.error)

        val wide = run(wide = true)
        assertTrue(wide.error ?: "", wide.success)
        assertEquals("full", wide.state["kind"])
        assertTrue(waitFor(File(root, "asked")))
        assertEquals("-b", File(root, "asked").readText().trim())
    }
}
