package com.nbtaad.editor.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipSurveyTest {
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

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun text(s: String) = s.toByteArray()

    /** The file set of an add-on like nbt-aa-tile, with a checksum list that is right. */
    private fun addOn(dir: String = "tile/", mutate: (MutableMap<String, ByteArray>) -> Unit = {}): ByteArray {
        val files = linkedMapOf(
            "aa-tile" to elf(),
            "aa-tile-launcher.sh" to text("#!/bin/sh\nexec /opt/aa-tile/aa-tile\n"),
            "install-boot.sh" to text("#!/bin/sh\ncp /opt/conn/bin/start_webserver.sh /opt/aa-tile/start_webserver.sh.original\n"),
            "remove-boot.sh" to text("#!/bin/sh\necho removed\n"),
            "hook.txt" to text("# hook - removed by remove-boot.sh\n"),
        )
        val sums = files.entries.joinToString("") { "${sha(it.value)}  ${it.key}\n" }
        mutate(files)
        val all = files.map { "$dir${it.key}" to it.value } + ("${dir}SHA256SUMS" to text(sums))
        return zipOf(*all.toTypedArray())
    }

    // ---- what a file is ----

    @Test
    fun filesAreToldApartByTheirFirstBytes() {
        assertEquals(EntryKind.PROGRAM, ZipSurvey.classify("a", elf()))
        assertEquals(EntryKind.ELF, ZipSurvey.classify("a", elf(qnx = false)))
        assertEquals(EntryKind.ELF, ZipSurvey.classify("a", elf(machine = 3)))
        assertEquals(EntryKind.SCRIPT, ZipSurvey.classify("run", text("#!/bin/sh\necho hi\n")))
        assertEquals(EntryKind.SCRIPT, ZipSurvey.classify("x.sh", text("echo hi\n")))
        assertEquals(EntryKind.TEXT, ZipSurvey.classify("README.md", text("# Title\nsome words\n")))
        assertEquals(EntryKind.ARCHIVE, ZipSurvey.classify("a.bin", zipOf("x" to text("y"))))
        assertEquals(EntryKind.ARCHIVE, ZipSurvey.classify("a.tar.gz", byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertEquals(EntryKind.PICTURE, ZipSurvey.classify("a.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(EntryKind.OTHER, ZipSurvey.classify("blob", byteArrayOf(0, 1, 2, 3, 0, 0, 9, 9)))
    }

    // ---- an add-on ----

    @Test
    fun anAddOnZipIsListedFileByFileWithItsKinds() {
        val s = ZipSurvey.survey("tile.zip", addOn())
        assertEquals(6, s.entries.size)
        assertEquals(EntryKind.PROGRAM, s.find("aa-tile")!!.kind)
        assertEquals(EntryKind.SCRIPT, s.find("install-boot.sh")!!.kind)
        assertEquals(EntryKind.TEXT, s.find("hook.txt")!!.kind)
        assertEquals("tile/aa-tile", s.find("aa-tile")!!.path)
        assertEquals(2200L, s.find("aa-tile")!!.size)
        assertEquals(sha(elf()), s.find("aa-tile")!!.sha256)
        assertTrue(s.find("install-boot.sh")!!.text!!.contains("start_webserver.sh.original"))
        assertNull(s.find("aa-tile")!!.text)
        assertNull(s.refused)
    }

    @Test
    fun theChecksumListThatComesWithTheFilesIsChecked() {
        val s = ZipSurvey.survey("tile.zip", addOn())
        val sums = s.sums!!
        assertEquals("SHA256SUMS", sums.file)
        assertEquals(5, sums.listed)
        assertTrue(sums.allMatch)
    }

    @Test
    fun aChangedFileIsFoundByTheChecksumList() {
        val s = ZipSurvey.survey("tile.zip", addOn { it["remove-boot.sh"] = text("#!/bin/sh\nrm -rf /\n") })
        assertEquals(listOf("remove-boot.sh"), s.sums!!.different)
        assertFalse(s.sums!!.allMatch)
        val r = AddOns.recognize(s)
        assertTrue(r.problems.any { it.contains("remove-boot.sh") && it.contains("do not match") })
    }

    @Test
    fun aFileMissingFromTheZipIsFoundByTheChecksumList() {
        val s = ZipSurvey.survey("tile.zip", addOn { it.remove("hook.txt") })
        // the list was made with all five files, the zip has four
        assertEquals(listOf("hook.txt"), s.sums!!.absent)
        assertTrue(AddOns.recognize(s).problems.any { it.contains("hook.txt") })
    }

    @Test
    fun theTileAddOnIsRecognizedButNotApplied() {
        val r = AddOns.recognize(ZipSurvey.survey("tile.zip", addOn()))
        assertEquals(Match.KNOWN_ADDON, r.match)
        assertEquals("nbt-aa-tile", r.addOn)
        assertFalse(r.installable)
        assertFalse(r.appliesInApp)
        assertTrue(r.problems.isEmpty())
        assertTrue(r.facts.any { it.contains("cannot apply") })
        assertTrue(r.facts.any { it.contains("all 5 listed files match") })
    }

    @Test
    fun theAddOnNeedsItsProgramToBeAProgramForTheHeadUnit() {
        val s = ZipSurvey.survey("tile.zip", addOn { it["aa-tile"] = elf(qnx = false) })
        assertEquals(Match.UNKNOWN, AddOns.recognize(s).match)
    }

    // ---- a build of the project ----

    private fun release(): ByteArray {
        val plugin = zipOf(
            "Releases/libNbtAad.so" to elf(type = 3, qnx = false),
            "Releases/NbtAad.manifest" to text("name=NbtAad\n"),
            "Releases/NbtAad.xml" to text("<?xml version=\"1.0\"?><app/>"),
            "Releases/NbtAad_ALL_Texts.zip" to zipOf("a.txt" to text("x")),
            "Releases/NbtAad_ALL_Images.zip" to zipOf("a.txt" to text("x")),
        )
        return zipOf("nbt-aad" to elf(extra = "NBT-AAD-VERSION=v1.9.91-test\u0000"), "Releases.zip" to plugin)
    }

    @Test
    fun aBuildIsRecognizedAndInstallable() {
        val s = ZipSurvey.survey("release.zip", release())
        val r = AddOns.recognize(s)
        assertEquals(Match.SQ00KY_BUILD, r.match)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertTrue(r.installable)
        assertTrue(r.facts.any { it.contains("v1.9.91-test") })
        // the zip inside the zip is opened and its files are listed under it
        assertNotNull(s.entries.firstOrNull { it.path == "Releases.zip/Releases/libNbtAad.so" })
    }

    @Test
    fun aBuildWithAWrongProgramIsNotInstallable() {
        val bad = zipOf("nbt-aad" to elf(machine = 3))
        val r = AddOns.recognize(ZipSurvey.survey("x.zip", bad))
        assertEquals(Match.SQ00KY_BUILD, r.match)
        assertFalse(r.installable)
        assertTrue(r.problems.any { it.contains("not a 32-bit ARM program") })
    }

    @Test
    fun aSingleFileIsItsOwnOnlyEntry() {
        val s = ZipSurvey.survey("nbt-aad", elf(extra = "NBT-AAD-VERSION=v1.0\u0000"))
        assertEquals(1, s.entries.size)
        assertEquals(EntryKind.PROGRAM, s.entries[0].kind)
        assertEquals(Match.SQ00KY_BUILD, AddOns.recognize(s).match)
    }

    // ---- something else ----

    @Test
    fun anUnknownZipIsOnlyDescribed() {
        val s = ZipSurvey.survey(
            "stuff.zip",
            zipOf("notes.txt" to text("hello\n"), "pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)),
        )
        val r = AddOns.recognize(s)
        assertEquals(Match.UNKNOWN, r.match)
        assertFalse(r.installable)
        assertEquals(EntryKind.PICTURE, s.find("pic.png")!!.kind)
        assertTrue(r.facts.first().startsWith("2 files"))
    }

    // ---- limits ----

    @Test
    fun anEntryWithAnUnsafePathIsNamedAndNotUsed() {
        val s = ZipSurvey.survey("x.zip", zipOf("../evil.sh" to text("#!/bin/sh\n"), "ok.txt" to text("fine\n")))
        assertEquals(listOf("ok.txt"), s.entries.map { it.path })
        assertTrue(s.notes.any { it.contains("unsafe path") })
    }

    @Test
    fun aZipWithTooManyEntriesIsLookedAtOnlyInPart() {
        val many = zipOf(*(1..450).map { "f$it.txt" to text("x") }.toTypedArray())
        val s = ZipSurvey.survey("many.zip", many)
        assertNotNull(s.refused)
        assertTrue(s.entries.size <= 400)
        assertTrue(AddOns.recognize(s).problems.isNotEmpty())
    }

    @Test
    fun archivesInsideArchivesStopAtTheDepthLimit() {
        val deep = zipOf("a.zip" to zipOf("b.zip" to zipOf("c.zip" to zipOf("d.txt" to text("x")))))
        val s = ZipSurvey.survey("deep.zip", deep)
        assertTrue(s.notes.any { it.startsWith("Did not open") })
        assertNull(s.entries.firstOrNull { it.base == "d.txt" })
    }

    @Test
    fun aDamagedZipDoesNotThrow() {
        val good = addOn()
        val s = ZipSurvey.survey("bad.zip", good.copyOf(good.size / 2))
        assertTrue(s.entries.size < 6)
    }

    @Test
    fun checksumLinesAreReadTheWayShaSumWritesThem() {
        val h = "a".repeat(64)
        val lines = ZipSurvey.parseSums("$h  one\n${"b".repeat(64)} *two.sh\n\nnot a line\n${"C".repeat(64)}  ./three\n")
        assertEquals(listOf(h to "one", "b".repeat(64) to "two.sh", "c".repeat(64) to "three"), lines)
    }
}
