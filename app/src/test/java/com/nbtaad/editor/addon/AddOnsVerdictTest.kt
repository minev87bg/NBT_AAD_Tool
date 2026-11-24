package com.nbtaad.editor.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the window says first about a file: ready to try, cannot be installed, or not a build. */
class AddOnsVerdictTest {
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

    private fun text(s: String) = s.toByteArray()

    private fun buildEntries(daemon: ByteArray = elf(extra = "NBT-AAD-VERSION=v1.9.82-test")): Array<Pair<String, ByteArray>> = arrayOf(
        "nbt-aad" to daemon,
        "release/libNbtAad.so" to elf(type = 3, qnx = false),
        "NbtAad.manifest" to text("name=NbtAad\n"),
        "NbtAad.xml" to text("<plugin/>"),
        "NbtAad_ALL_Texts.zip" to zipOf("a.txt" to text("x")),
        "NbtAad_ALL_Images.zip" to zipOf("a.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
    )

    @Test
    fun aCompleteBuildIsReadyToTryAndSaysWhatWasChecked() {
        val r = AddOns.recognize(ZipSurvey.survey("b.zip", zipOf(*buildEntries())))
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertTrue(r.installable)
        assertEquals(Recognition.Level.READY, r.level)
        assertTrue(r.verdict, r.verdict.startsWith("Installable now"))
        assertTrue(r.verdict, r.verdict.contains("6 of 6"))
        assertEquals("Installable now", r.headline)
        assertTrue(r.answer, r.answer.startsWith("Yes"))
        val b = r.build!!
        assertEquals(100, b.percent)
        assertEquals(6, b.have.size)
        assertTrue(b.missing.isEmpty())
        assertEquals("v1.9.82-test", b.version)
        assertEquals(5, b.pluginHave.size)
        assertTrue(r.facts.toString(), r.facts.any { it.contains("32-bit ARM program") })
        assertTrue(r.facts.toString(), r.facts.any { it.contains("libNbtAad.so is a 32-bit ARM library") })
    }

    @Test
    fun aBuildWithAWrongProgramCannotBeInstalled() {
        val r = AddOns.recognize(ZipSurvey.survey("b.zip", zipOf(*buildEntries(daemon = text("not a program at all")))))
        assertFalse(r.installable)
        assertEquals(Recognition.Level.NO, r.level)
        assertTrue(r.verdict, r.verdict.startsWith("Cannot be installed"))
        assertEquals("Cannot be installed", r.headline)
        assertTrue(r.answer, r.answer.startsWith("No"))
    }

    @Test
    fun aPartialBuildSaysWhatIsMissing() {
        val r = AddOns.recognize(ZipSurvey.survey("b.zip", zipOf("nbt-aad" to elf(extra = "NBT-AAD-VERSION=v1.9.82-test"))))
        val b = r.build!!
        assertEquals(17, b.percent)
        assertEquals(listOf("nbt-aad"), b.have)
        assertEquals(5, b.pluginMissing.size)
        assertTrue(b.pluginHave.isEmpty())
    }

    @Test
    fun aZipWithoutBuildFilesIsNotABuild() {
        val r = AddOns.recognize(ZipSurvey.survey("x.zip", zipOf("readme.txt" to text("hello\n"), "tool" to elf())))
        assertFalse(r.installable)
        assertEquals(Recognition.Level.NOT_HERE, r.level)
        assertTrue(r.verdict, r.verdict.startsWith("Not an NBT-AAD build"))
        assertEquals("Not an NBT-AAD build", r.headline)
        assertTrue(r.answer, r.answer.startsWith("No"))
        assertNull(r.build)
    }

    @Test
    fun theFilesInsideThePluginsOwnZipsAreToldApart() {
        val s = ZipSurvey.survey("b.zip", zipOf(*buildEntries()))
        val nested = s.entries.filter { AddOns.inBuildZip(it) }
        assertTrue(s.entries.map { it.path }.toString(), nested.isNotEmpty())
        assertTrue(nested.all { it.path.contains("NbtAad_ALL_") })
        assertFalse(AddOns.inBuildZip(s.find("nbt-aad")!!))
        assertFalse(AddOns.inBuildZip(s.find("libNbtAad.so")!!))
    }
}
