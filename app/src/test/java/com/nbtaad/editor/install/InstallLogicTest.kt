package com.nbtaad.editor.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallLogicTest {
    // ---- invented files: the shape of the real ones, none of their content ----

    /** A 32-bit little-endian ELF header; [type] 2 = program, 3 = library; [machine] 40 = ARM. */
    private fun elf(type: Int = 2, machine: Int = 40, qnx: Boolean = true, extra: String = ""): ByteArray {
        val b = ByteArray(2200)
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 1; b[5] = 1; b[6] = 1
        b[16] = type.toByte(); b[17] = (type shr 8).toByte()
        b[18] = machine.toByte(); b[19] = (machine shr 8).toByte()
        if (qnx) "/usr/lib/ldqnx.so.2".toByteArray().copyInto(b, 300)
        extra.toByteArray().copyInto(b, 1000)
        return b
    }

    private fun daemon(version: String = "v1.9.91-test") = elf(extra = "NBT-AAD-VERSION=$version\u0000")
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val tinyZip = zipOf("a.txt" to "x".toByteArray())

    private fun pluginEntries(prefix: String = "Releases/"): Array<Pair<String, ByteArray>> = arrayOf(
        "${prefix}libNbtAad.so" to elf(type = 3, qnx = false),
        "${prefix}NbtAad.manifest" to "name=NbtAad\n".toByteArray(),
        "${prefix}NbtAad.xml" to "<?xml version=\"1.0\"?><app/>".toByteArray(),
        "${prefix}NbtAad_ALL_Texts.zip" to tinyZip,
        "${prefix}NbtAad_ALL_Images.zip" to tinyZip,
    )

    private fun release(): List<SourceFile> = listOf(
        SourceFile("nbt-aad", daemon()),
        SourceFile("Releases.zip", zipOf(*pluginEntries())),
    )

    // ---- names ----

    @Test
    fun namesAreMatchedLikeABrowserSavesThem() {
        assertEquals("nbt-aad", Names.canonical("nbt-aad"))
        assertEquals("nbt-aad", Names.canonical("nbt-aad (1)"))
        assertEquals("nbt-aad", Names.canonical("download/NBT-AAD"))
        assertEquals("NbtAad_ALL_Texts.zip", Names.canonical("NbtAad_ALL_Texts (2).zip"))
        assertEquals("libNbtAad.so", Names.canonical("Releases/libnbtaad.so"))
        assertNull(Names.canonical("nbt-aad.old"))
        assertNull(Names.canonical("readme.md"))
        assertTrue(Names.isContainer("Releases (3).zip"))
        assertFalse(Names.isContainer("NbtAad_ALL_Texts.zip"))
        assertEquals(6, Names.ALL.size)
    }

    // ---- inspection and the 80 % rule ----

    @Test
    fun aReleaseIsABuild() {
        val p = BuildPackage.of(release())
        assertEquals(Kind.BUILD, p.kind)
        assertEquals(6, p.found.size)
        assertTrue(p.missing.isEmpty())
        assertEquals(100, p.percent)
        assertEquals("v1.9.91-test", p.version)
        assertTrue(p.fullPlugin)
        assertTrue(p.problems.toString(), p.problems.isEmpty())
        assertTrue(p.installable)
    }

    @Test
    fun theSameFilesInOneBiggerZipAreABuildToo() {
        val inner = zipOf(*pluginEntries())
        val big = zipOf("build/nbt-aad" to daemon(), "build/pf_aa" to elf(), "build/Releases.zip" to inner, "build/notes.txt" to "hi".toByteArray())
        val p = BuildPackage.of(listOf(SourceFile("test-build.zip", big)))
        assertEquals(Kind.BUILD, p.kind)
        assertEquals(100, p.percent)
        assertTrue(p.installable)
        assertTrue(p.payloads.getValue("libNbtAad.so").from.contains("Releases.zip"))
    }

    @Test
    fun theEightyPercentLine() {
        // 4 of 6 files (67 %) is below the line: the plugin missing two of its five files
        val four = listOf(SourceFile("nbt-aad", daemon())) + pluginEntries().take(3).map { SourceFile(it.first, it.second) }
        val p = BuildPackage.of(four)
        assertEquals(4, p.found.size)
        assertEquals(Kind.SOME_FILES, p.kind)
        assertTrue(p.warnings.any { it.contains("4 of 6") })
        assertFalse(p.fullPlugin)
        assertTrue(p.installable)
        // 5 of 6 (83 %) is over the line: a build, even with one plugin file missing
        val five = four + SourceFile(pluginEntries()[3].first, pluginEntries()[3].second)
        assertEquals(Kind.BUILD, BuildPackage.of(five).kind)
    }

    @Test
    fun aSingleDaemonIsATestBuild() {
        val p = BuildPackage.of(listOf(SourceFile("nbt-aad (1)", daemon("v2.0.0-x"))))
        assertEquals(Kind.DAEMON_ONLY, p.kind)
        assertEquals(17, p.percent)
        assertEquals("v2.0.0-x", p.version)
        assertTrue(p.installable)
    }

    @Test
    fun aPluginOnlyUpdateIsRecognised() {
        val p = BuildPackage.of(listOf(SourceFile("Releases.zip", zipOf(*pluginEntries()))))
        assertEquals(Kind.PLUGIN_ONLY, p.kind)
        assertTrue(p.fullPlugin)
        assertTrue(p.installable)
    }

    @Test
    fun otherFilesAreNotABuild() {
        val p = BuildPackage.of(listOf(SourceFile("logs.zip", zipOf("nbt-aad.log" to "x".toByteArray())), SourceFile("photo.jpg", ByteArray(40))))
        assertEquals(Kind.NOTHING, p.kind)
        assertFalse(p.installable)
        assertEquals(0, p.percent)
    }

    // ---- checks of each file ----

    @Test
    fun aProgramForAnotherMachineIsRefused() {
        val x86 = BuildPackage.of(listOf(SourceFile("nbt-aad", elf(machine = 3))))
        assertFalse(x86.installable)
        assertTrue(x86.problems.single().contains("ARM"))
        val text = BuildPackage.of(listOf(SourceFile("nbt-aad", "#!/bin/sh\necho hi\n".toByteArray())))
        assertFalse(text.installable)
        assertTrue(text.problems.single().contains("ELF"))
        val empty = BuildPackage.of(listOf(SourceFile("nbt-aad", ByteArray(0))))
        assertFalse(empty.installable)
    }

    @Test
    fun aDaemonWithoutTheQnxLoaderOnlyGetsAWarning() {
        val p = BuildPackage.of(listOf(SourceFile("nbt-aad", elf(qnx = false))))
        assertTrue(p.installable)
        assertTrue(p.warnings.any { it.contains("ldqnx") })
    }

    @Test
    fun theOtherFilesAreChecked() {
        val badLib = BuildPackage.of(listOf(SourceFile("libNbtAad.so", elf(type = 2))))
        assertFalse(badLib.installable)
        assertFalse(BuildPackage.of(listOf(SourceFile("NbtAad_ALL_Texts.zip", "nope".toByteArray()))).installable)
        assertFalse(BuildPackage.of(listOf(SourceFile("NbtAad.xml", "not xml".toByteArray()))).installable)
        assertFalse(BuildPackage.of(listOf(SourceFile("NbtAad.manifest", ByteArray(100)))).installable)
        assertTrue(BuildPackage.of(listOf(SourceFile("NbtAad.xml", "﻿  <a/>".toByteArray()))).installable)
    }

    @Test
    fun theVersionIsReadFromTheDaemon() {
        assertEquals("v1.9.91-sppreq3", BuildPackage.findVersion("xxNBT-AAD-VERSION=v1.9.91-sppreq3\u0000yy".toByteArray()))
        assertNull(BuildPackage.findVersion("NBT-AAD-VERSION=banana".toByteArray()))
        assertNull(BuildPackage.findVersion("nothing here".toByteArray()))
        assertNull(BuildPackage.findVersion(ByteArray(3)))
    }

    // ---- hostile archives ----

    @Test
    fun entriesWithUnsafePathsAreSkipped() {
        val z = zipOf("../../nbt-aad" to daemon(), "/abs/pf_aa" to elf(), "ok/NbtAad.xml" to "<a/>".toByteArray())
        val p = BuildPackage.of(listOf(SourceFile("x.zip", z)))
        assertEquals(listOf("NbtAad.xml"), p.found)
        assertTrue(p.inspection.notes.count { it.contains("unsafe") } == 2)
    }

    @Test
    fun aDuplicateKeepsTheShallowerFile() {
        val z = zipOf("deep/er/nbt-aad" to daemon("v1.0.0-deep"), "nbt-aad" to daemon("v1.0.1-top"))
        val p = BuildPackage.of(listOf(SourceFile("x.zip", z)))
        assertEquals("v1.0.1-top", p.version)
        assertTrue(p.inspection.notes.any { it.contains("twice") })
    }

    @Test
    fun tooManyEntriesOrTooMuchDataIsRefused() {
        val many = zipOf(*Array(Limits.ENTRIES + 5) { "f$it.txt" to ByteArray(1) })
        val p = BuildPackage.of(listOf(SourceFile("many.zip", many)))
        assertNotNull(p.inspection.refused)
        assertFalse(p.installable)
        // an entry above the per-file cap: a long run of zeros compresses to almost nothing
        val bomb = zipOf("nbt-aad" to ByteArray((Limits.ENTRY + 1).toInt()))
        assertTrue(bomb.size < 200_000)
        val q = BuildPackage.of(listOf(SourceFile("bomb.zip", bomb)))
        assertNotNull(q.inspection.refused)
        assertFalse(q.installable)
    }

    @Test
    fun aBrokenZipDoesNotCrash() {
        val z = zipOf("nbt-aad" to daemon())
        val cut = z.copyOf(40)   // the header of the first entry and a few bytes of its data
        val p = BuildPackage.of(listOf(SourceFile("cut.zip", cut)))
        assertFalse(p.installable)
        assertTrue(p.inspection.notes.isNotEmpty() || p.found.isEmpty())
    }

    @Test
    fun archivesInsideArchivesStopAtTwoLevels() {
        val l3 = zipOf("NbtAad.xml" to "<a/>".toByteArray())
        val l2 = zipOf("c.zip" to l3)
        val l1 = zipOf("b.zip" to l2)
        val p = BuildPackage.of(listOf(SourceFile("a.zip", l1)))
        assertEquals(emptyList<String>(), p.found)
        assertTrue(p.inspection.notes.any { it.contains("archive") })
    }

    // ---- reading what the head unit prints ----

    @Test
    fun theFoldersAreLocatedFromTheScriptOutput() {
        val both = "P \nAA /opt/aa\nP /net/hu-omap/fs/sda0\nAA /net/hu-omap/fs/sda0/opt/aa\n" +
            "PL /net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad\nP /net/hu-omap\n"
        assertEquals(
            "/net/hu-omap/fs/sda0/opt/aa" to "/net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad",
            RemoteScripts.parseLocate(both),
        )
        assertEquals("/opt/aa" to null, RemoteScripts.parseLocate("P\nAA /opt/aa\nP /x\n"))
        assertEquals(null to null, RemoteScripts.parseLocate("P\nP /x\n"))
        assertEquals(null to null, RemoteScripts.parseLocate(""))
    }

    @Test
    fun theStatusIsReadFromTheScriptOutput() {
        val out = "F nbt-aad x 8421376\nF nbt-aad.old - 8000000\nOLDPLUGIN\nL libNbtAad.so 1234\nL NbtAad.xml 5\n" +
            "NBT-AAD-VERSION=v1.9.91-x\n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", out)
        assertTrue(s.hasDaemon)
        assertTrue(s.hasPreviousDaemon)
        assertTrue(s.hasOldPlugin)
        assertTrue(s.hasPrevious)
        assertEquals(8421376L, s.files.getValue("nbt-aad").size)
        assertTrue(s.files.getValue("nbt-aad").executable)
        assertFalse(s.files.getValue("nbt-aad.old").executable)
        assertFalse(s.daemonBroken)
        assertEquals(2, s.pluginFiles.size)
        assertEquals("v1.9.91-x", s.version)
        val none = RemoteScripts.parseStatus("/opt/aa", null, "")
        assertFalse(none.hasDaemon)
        assertFalse(none.hasPrevious)
        assertNull(none.version)
    }

    @Test
    fun theSettingsFileIsReadFromBetweenItsMarkers() {
        // what the file says never counts as status: "F ..." or "L ..." lines inside it are just text
        val out = "F nbt-aad x \nNBT-AAD-VERSION=v1.9.91\nENV-BEGIN\n# my settings\nNBT_A=1\nL not a plugin file\nNBT_B=two words\n\nENV-END\n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", out)
        assertEquals("# my settings\nNBT_A=1\nL not a plugin file\nNBT_B=two words\n", s.envText)
        assertFalse(s.envMissing)
        assertTrue("a line of the file is no plugin file", s.pluginFiles.isEmpty())
        assertEquals("v1.9.91", s.version)
        assertTrue(s.hasDaemon)
    }

    @Test
    fun aMissingOrEmptySettingsFileIsToldApart() {
        val none = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \nENV-NONE\n")
        assertNull(none.envText)
        assertTrue(none.envMissing)
        val empty = RemoteScripts.parseStatus("/opt/aa", "/p", "ENV-BEGIN\n\nENV-END\n")
        assertEquals("", empty.envText)
        assertFalse(empty.envMissing)
        // not asked for: nothing is known either way
        val notAsked = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \n")
        assertNull(notAsked.envText)
        assertFalse(notAsked.envMissing)
    }

    @Test
    fun aSettingsFileThatIsCutOffIsNotTrusted() {
        // no end marker: the connection or the command stopped halfway, so what arrived may be missing settings
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \nENV-BEGIN\nNBT_A=1\n")
        assertNull(s.envText)
        assertTrue(s.hasDaemon)
    }

    @Test
    fun aFileWithoutALineBreakAtTheEndIsReadWhole() {
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", "ENV-BEGIN\nNBT_A=1\nNBT_B=2\nENV-END\n")
        assertEquals("NBT_A=1\nNBT_B=2\n", s.envText)
        val crlf = RemoteScripts.parseStatus("/opt/aa", "/p", "ENV-BEGIN\r\nNBT_A=1\r\n\r\nENV-END\r\n")
        assertEquals("NBT_A=1\n", crlf.envText)
    }

    @Test
    fun theStatusScriptAsksForTheSettingsFileOnlyWhenWanted() {
        val plain = RemoteScripts.status("/opt/aa", "/p")
        assertFalse(plain.contains("ENV-BEGIN"))
        val withEnv = RemoteScripts.status("/opt/aa", "/p", withEnv = true)
        assertTrue(withEnv.contains("cat /opt/aa/nbt-aad.env"))
        assertTrue(withEnv.contains("ENV-NONE"))
        assertFalse(withEnv.contains("\u00A4"))
        assertFalse(withEnv.contains("@AA@"))
        // the file is read with cat and the shell's own tests, which the car's login has
        val envLines = withEnv.lines().dropWhile { !it.contains("ENV-BEGIN") || it.contains("echo").not() }
        assertTrue(envLines.none { it.contains("grep") || it.contains("wc ") })
    }

    @Test
    fun theDaemonThatWasReadBackIsPartOfTheStatus() {
        val base = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \n")
        assertNull(base.installedVersion)
        val withFile = base.withDaemonFile(DaemonFile(900_000, "ab".repeat(32), "v1.9.112"))
        // the head unit's own tools found no version (the car's grep): the one read from the file is used
        assertEquals("v1.9.112", withFile.installedVersion)
        assertFalse(withFile.daemonUnreadable)
        assertTrue(withFile.describe().contains("daemon read back 900000 bytes"))
        // and what its own tools found wins
        val named = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \nNBT-AAD-VERSION=v1.9.1\n")
            .withDaemonFile(DaemonFile(1, "cd".repeat(32), "v2.0.0"))
        assertEquals("v1.9.1", named.installedVersion)
        val failed = base.withDaemonFile(null)
        assertTrue(failed.daemonUnreadable)
        assertNull(failed.daemonFile)
        // the settings text survives the copy
        val env = RemoteScripts.parseStatus("/opt/aa", "/p", "ENV-BEGIN\nNBT_A=1\nENV-END\n").withDaemonFile(null)
        assertEquals("NBT_A=1\n", env.envText)
    }

    @Test
    fun aHeadUnitWithoutWcStillShowsItsFiles() {
        // the car's SSH login has no wc: the sizes are empty, the files must still be listed
        val out = "F nbt-aad x \nF nbt-aad.old x \nL libNbtAad.so \nL NbtAad.xml \n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", out)
        assertTrue(s.hasDaemon)
        assertEquals(-1L, s.files.getValue("nbt-aad").size)
        assertTrue(s.hasPreviousDaemon)
        assertEquals(listOf("libNbtAad.so", "NbtAad.xml"), s.pluginFiles.keys.toList())
        assertEquals(-1L, s.pluginFiles.getValue("NbtAad.xml"))
    }

    @Test
    fun aDaemonThatCannotBeRunIsReported() {
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad - 100\nF nbt-aad.old x 100\n")
        assertTrue(s.daemonBroken)
        assertTrue(s.hasPreviousDaemon)
    }

    @Test
    fun theDaemonProcessIsFoundInTheProcessScan() {
        val out = "SCAN /proc\nSCAN /net/hu-omap/proc\nPID /net/hu-omap/proc 19320\nPID /proc 77\nPID /proc 77\n"
        val p = RemoteScripts.parseProc(out)
        assertTrue(p.scanned)
        assertTrue(p.any)
        assertEquals(listOf(77), p.here)
        assertEquals(listOf(19320), p.onHuOmap)
        val none = RemoteScripts.parseProc("SCAN /proc\n")
        assertTrue(none.scanned)
        assertFalse(none.any)
        assertFalse(RemoteScripts.parseProc("").scanned)
    }

    @Test
    fun pathsAreTranslatedForTheOtherProcessor() {
        assertEquals("/fs/sda0/opt/aa", RemoteScripts.onHuOmapPath("/net/hu-omap/fs/sda0/opt/aa"))
        assertEquals("/opt/aa", RemoteScripts.onHuOmapPath("/opt/aa"))
        assertEquals("/opt/aa", RemoteScripts.onHuOmapPath("/net/hu-omap/opt/aa"))
    }

    @Test
    fun theScriptsUseOnlyTheCommandsTheCarHas() {
        // cat, cp, mv, rm, mkdir and the shell itself; the others only as optional extras, never to decide anything
        val needed = listOf(
            RemoteScripts.swapDaemon("/opt/aa"), RemoteScripts.swapPlugin("/opt/aa", "/p"),
            RemoteScripts.revertDaemon("/opt/aa"), RemoteScripts.revertPlugin("/opt/aa", "/p"),
            RemoteScripts.prepare("/opt/aa", "/p", daemon = true, plugin = true, keepOld = true),
            RemoteScripts.procScan(), RemoteScripts.locate(), RemoteScripts.probe(),
        )
        for (sc in needed) {
            for (bad in listOf("wc ", "cksum", "basename", "pidin", "ls ", "head", "sleep", "dd ", "cmp ")) {
                assertFalse("$bad in:\n$sc", sc.lines().any { !it.trimStart().startsWith("PATH=") && it.contains(bad) })
            }
        }
        assertTrue(RemoteScripts.swapDaemon("/opt/aa").startsWith("PATH="))
        assertFalse(RemoteScripts.swapDaemon("/opt/aa").contains("\u00A4"))
    }

    @Test
    fun anInstallIsAFewCommandsNotOnePerFile() {
        // each command costs the head unit a good part of a second: look, prepare, uploads, check, replace
        val all = listOf(
            RemoteScripts.probe(),
            RemoteScripts.prepare("/opt/aa", "/p", daemon = true, plugin = true, keepOld = false),
            RemoteScripts.check("/opt/aa", true, listOf("libNbtAad.so", "NbtAad.xml")),
            RemoteScripts.apply("/opt/aa", "/p", listOf(RemoteScripts.Op.SWAP_DAEMON, RemoteScripts.Op.SWAP_PLUGIN), false),
        )
        for (sc in all) assertFalse(sc.contains("\u00A4"))
        val c = RemoteScripts.check("/opt/aa", true, listOf("libNbtAad.so", "../evil; rm -rf /"))
        assertTrue(c.contains("NbtAad.new/libNbtAad.so"))
        assertFalse("only the names of a build are listed", c.contains("evil"))
        val ap = RemoteScripts.apply("/opt/aa", "/p", listOf(RemoteScripts.Op.SWAP_DAEMON, RemoteScripts.Op.SWAP_PLUGIN), false)
        assertTrue(ap.contains("swap_daemon || exit"))
        assertTrue(ap.contains("echo \"DONE plugin\""))
        assertTrue(ap.indexOf("swap_daemon ||") < ap.indexOf("swap_plugin ||"))
    }

    @Test
    fun theCheckIsReadFromTheListing() {
        val out = "EXEC 0\n" +
            "-rwxr-xr-x    1 root      root        904668 Nov 18 22:45 nbt-aad.new\n" +
            "-rw-r--r--    1 root      root          1234 Nov 18 22:45 NbtAad.new/libNbtAad.so\n"
        val c = RemoteScripts.parseCheck(out)
        assertEquals(0, c.exec)
        assertTrue(c.sizeIs("nbt-aad.new", 904668))
        assertTrue(c.sizeIs("libNbtAad.so", 1234))
        assertFalse("a size that differs", c.sizeIs("libNbtAad.so", 1235))
        assertFalse("a file that is not listed", c.sizeIs("NbtAad.xml", 5))
        // no ls at all: nothing is confirmed, so everything gets read back
        val none = RemoteScripts.parseCheck("EXEC 14\n")
        assertEquals(14, none.exec)
        assertFalse(none.sizeIs("nbt-aad.new", 1))
        assertNull(RemoteScripts.parseCheck("").exec)
    }

    @Test
    fun theResultOfTheReplacingIsRead() {
        val out = "DONE daemon\nDONE plugin\nF nbt-aad x \nOLDPLUGIN\nL libNbtAad.so \nNBT-AAD-VERSION=v1.9.1\n"
        assertEquals(true to true, RemoteScripts.parseDone(out))
        assertEquals(true to false, RemoteScripts.parseDone("DONE daemon\n"))
        assertEquals(false to false, RemoteScripts.parseDone(""))
        val s = RemoteScripts.parseStatus("/a", "/p", out)
        assertTrue(s.hasDaemon)
        assertEquals("v1.9.1", s.version)
        assertTrue(RemoteScripts.remountFailed("REMOUNT failed\n"))
        assertFalse(RemoteScripts.remountFailed("REMOUNT ok\n"))
    }

    // ---- releases ----

    @Test
    fun onlyTheFilesOfABuildAreDownloaded() {
        fun a(n: String, s: Long = 10) = AssetInfo(n, s, "https://x/$n", null)
        val r = ReleaseInfo(
            "v1.9.91", "t", false, "2026-06-01T10:00:00Z", "", "u",
            listOf(a("nbt-aad", 8_000_000), a("Releases.zip", 2_000_000), a("com.github.sq00ky.android-auto-1.9.91.nbtpkg"), a("nbtpkg-record.json")),
        )
        assertEquals(listOf("nbt-aad", "Releases.zip"), ReleaseLogic.wanted(r).map { it.name })
        assertTrue(ReleaseLogic.installable(r))
        assertEquals(10_000_000L, ReleaseLogic.downloadSize(r))
        assertFalse(ReleaseLogic.installable(r.copy(assets = listOf(a("notes.txt")))))
        assertEquals("2026-06-01", ReleaseLogic.day(r.published))
        assertEquals("1.0 MB".length, ReleaseLogic.mb(1024 * 1024).length)
    }

    @Test
    fun theInfoOfARelease() {
        fun a(n: String, s: Long) = AssetInfo(n, s, "https://x/$n", null)
        val notes = "Fixes the video stretch on wide screens when the phone is turned.\r\n\r\n\r\n" +
            "- A list item that is longer than the width of the window and has to go on\r\n" +
            "https://github.com/Sq00ky/Supra-iDrive6-Android-Auto/releases/tag/v1.9.116"
        val r = ReleaseInfo(
            "v1.9.116", "Video fix", true, "2026-11-20T20:59:00Z", notes, "https://github.com/x/y",
            listOf(a("nbt-aad", 1_048_576), a("Releases.zip", 2048), a("notes.txt", 5)),
        )
        val t = ReleaseLogic.infoText(r, "2026-11-20 20:59  ·  1 h ago", width = 40)
        val lines = t.lines()
        assertEquals("v1.9.116", lines[0])
        assertEquals("Video fix", lines[1])
        assertTrue(t.contains("Published: 2026-11-20 20:59  ·  1 h ago"))
        assertTrue(t.contains("Pre-release: yes"))
        assertTrue(t.contains("  nbt-aad  1.0 MB"))
        assertTrue(t.contains("  Releases.zip  2 KB"))
        assertFalse(t.contains("notes.txt"))
        assertTrue(t.contains("Page: https://github.com/x/y"))
        val body = t.substringAfter("-------------\n")
        assertFalse(body.contains("\r"))
        assertFalse(body.contains("\n\n\n"))
        // the notes are broken at spaces, a list item's next line sits under its text, a long link stays whole
        for (l in body.lines()) assertTrue(l, l.length <= 40 || !l.contains(' '))
        assertTrue(body, body.contains("- A list item that is longer than the\n  width of the window and has to go on\n"))
        assertTrue(body.endsWith("v1.9.116"))
        assertTrue(ReleaseLogic.infoText(r.copy(notes = "  ", title = "v1.9.116", prerelease = false), "x").endsWith("(none written)"))
    }

    // ---- Android Auto installed by NBTApps ----

    @Test
    fun androidAutoInstalledByNbtAppsIsFoundInItsOwnFolders() {
        val id = RemoteScripts.NBTAPPS_ID
        // the login sees the other processor's folders as /net/hu-omap/...: no /opt/aa, NBTApps' folders and the plugin
        val out = "P \nP /net/hu-omap/fs/sda0\nPL /net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad\n" +
            "P /net/hu-omap\nNA /net/hu-omap/var/opt/nbtapp/apps/$id\nND /net/hu-omap/var/opt/nbtapp/data/$id\n"
        val l = RemoteScripts.parseLocated(out)
        assertNull(l.aa)
        assertEquals("/net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad", l.plugin)
        assertEquals("/net/hu-omap/var/opt/nbtapp/apps/$id", l.nbtApp)
        assertEquals("/net/hu-omap/var/opt/nbtapp/data/$id", l.nbtData)
        // a manual install still wins and has no NBTApps folders
        val manual = RemoteScripts.parseLocated("P \nAA /opt/aa\nPL /opt/hmi/ID5/data/ro/Plugins/NbtAad\nP /net/hu-omap/fs/sda0\n")
        assertEquals("/opt/aa", manual.aa)
        assertNull(manual.nbtApp)
        assertNull(manual.nbtData)
        // the old pair is unchanged by the new lines
        assertEquals(null to "/net/hu-omap/fs/sda0/opt/hmi/ID5/data/ro/Plugins/NbtAad", RemoteScripts.parseLocate(out))
        // the locate script asks for the folders, and the status script reads the env file where NBTApps keeps it
        assertTrue(RemoteScripts.locate().contains("/var/opt/nbtapp/apps/$id"))
        assertTrue(RemoteScripts.locate().contains("/var/opt/nbtapp/data/$id"))
        val st = RemoteScripts.status("/var/opt/nbtapp/apps/$id", null, withEnv = true, env = "/var/opt/nbtapp/data/$id")
        assertTrue(st.contains("cat /var/opt/nbtapp/data/$id/nbt-aad.env"))
        assertFalse(st.contains("@ENV@"))
        assertTrue(RemoteScripts.status("/opt/aa", null, withEnv = true).contains("cat /opt/aa/nbt-aad.env"))
        assertTrue(RemoteScripts.parseStatus("/a", null, "F nbt-aad x 5\n", nbtApps = true).nbtApps)
        assertFalse(RemoteScripts.parseStatus("/a", null, "F nbt-aad x 5\n").nbtApps)
    }

    // ---- the version from the daemon's RAM log ----

    @Test
    fun theVersionIsTakenFromTheLastBuildLineOfTheDaemonsLog() {
        val out = "F nbt-aad x \nBUILD-BEGIN\n  Build: v1.9.110 (Nov 17 2026 10:00:00)\n" +
            "  Build: v1.9.112 (Nov 18 2026 12:00:00): USB stick found while running, earlier lines were not saved\nBUILD-END\n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", out)
        assertEquals("v1.9.112", s.runningVersion)
        assertEquals("v1.9.112", s.installedVersion)
        assertNull(s.fileVersion)
        assertTrue(s.versionFromLog)
        assertTrue(s.hasDaemon)                       // the log lines did not disturb the file list
        assertEquals(1, s.files.size)
    }

    @Test
    fun noLogOrNothingUsableInItMeansNoVersion() {
        assertNull(RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \n").runningVersion)
        assertNull(RemoteScripts.parseStatus("/opt/aa", "/p", "BUILD-BEGIN\n\nBUILD-END\n").runningVersion)
        // other lines that happen to say "Build" are not the daemon's build line
        val other = "BUILD-BEGIN\nBuild failed\n  Rebuild: now (x)\nF evil x 5\nBUILD-END\nF nbt-aad x \n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", other)
        assertNull(s.runningVersion)
        assertEquals(setOf("nbt-aad"), s.files.keys)  // a line inside the log is not a file
        // a log that was cut off (no end marker) is not trusted
        assertNull(RemoteScripts.parseStatus("/opt/aa", "/p", "BUILD-BEGIN\n  Build: v1.9.1 (Nov 1 2026").runningVersion)
        assertNull(RemoteScripts.parseStatus("/opt/aa", "/p", "BUILD-BEGIN\n  Build: v1.9.1 (Nov 1 2026 1:00:00)\n").runningVersion)
    }

    @Test
    fun theLogAndTheEnvFileAreBothReadAndKeptApart() {
        val out = "F nbt-aad x \nBUILD-BEGIN\n  Build: v2.0.0-test (Nov 18 2026 12:00:00)\nBUILD-END\n" +
            "ENV-BEGIN\nNBT_A=1\nBuild: v9 (not this)\nENV-END\n"
        val s = RemoteScripts.parseStatus("/opt/aa", "/p", out)
        assertEquals("v2.0.0-test", s.runningVersion)
        assertEquals("NBT_A=1\nBuild: v9 (not this)\n", s.envText)
    }

    @Test
    fun theVersionInTheFileCountsMoreThanTheOneInTheLog() {
        val base = RemoteScripts.parseStatus("/opt/aa", "/p", "F nbt-aad x \nBUILD-BEGIN\n  Build: v1.9.112 (Nov 18 2026 12:00:00)\nBUILD-END\n")
        val read = base.withDaemonFile(DaemonFile(900_000, "ab".repeat(32), "v1.9.113"))
        assertEquals("v1.9.113", read.installedVersion)
        assertFalse(read.versionFromLog)
        assertEquals("v1.9.112", read.runningVersion)   // kept, to tell that Android Auto runs another build than the file
        // a file that names no version leaves the log's
        val none = base.withDaemonFile(DaemonFile(900_000, "ab".repeat(32), null))
        assertEquals("v1.9.112", none.installedVersion)
        assertTrue(none.versionFromLog)
    }

    @Test
    fun theStatusScriptAsksForTheDaemonsLogWithGrepAlone() {
        for (withEnv in listOf(false, true)) {
            val sh = RemoteScripts.status("/opt/aa", "/p", withEnv = withEnv)
            assertTrue(sh.contains("grep \"Build: \" \$d/nbt-aad.log"))
            // Android Auto runs on the hu-omap processor: its RAM is /net/hu-omap/dev/shmem from this login
            assertTrue(sh.contains("for d in /dev/shmem /net/hu-omap/dev/shmem; do"))
            assertTrue(sh.contains("BUILD-BEGIN"))
            assertTrue(sh.contains("BUILD-END"))
        }
    }

    @Test
    fun theLogFinderScriptLooksInRamAndOnTheStickOnBothProcessors() {
        val sh = RemoteScripts.findLogs()
        assertTrue(sh.contains("for d in /dev/shmem /net/hu-omap/dev/shmem /mnt/umass* /net/hu-omap/mnt/umass*; do"))
        assertTrue(sh.contains("ls -l \$d/nbt-aad*.log 2>/dev/null"))
        assertFalse(sh.contains("¤"))
    }
}
