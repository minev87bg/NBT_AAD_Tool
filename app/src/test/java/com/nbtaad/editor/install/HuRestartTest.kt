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
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private fun run(): TileScripts.Report {
        val f = File(root, "stage/job.sh")
        f.writeText(HuRestart.job(root = root.path, stage = File(root, "stage").path, delaySeconds = 0))
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
    }

    @Test
    fun aHeadUnitWithoutShutdownSaysSoAndTheyAreToldToHoldThePowerButton() {
        val r = run()
        assertTrue(r.finished)
        assertFalse(r.success)
        assertEquals(HuRestart.NO_COMMAND, r.error)
        assertTrue(HuRestart.NO_COMMAND.contains("power button"))
    }
}
