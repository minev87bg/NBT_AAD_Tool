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

/** The menu look kit (aa-menu-look), the label's rules and the picture checks. */
class MenuLookTest {
    private fun aaLook(qnx: Boolean = true, usage: Boolean = true, machine: Int = 40): ByteArray {
        val b = ByteArray(3000)
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 1; b[5] = 1; b[6] = 1
        b[16] = 2; b[18] = machine.toByte()
        if (qnx) "/usr/lib/ldqnx.so.2".toByteArray().copyInto(b, 300)
        if (usage) "usage: aa-look labels <bmw|bmwm|bmwi|mini> <src text.db> <dst text.db> [label]".toByteArray().copyInto(b, 1000)
        return b
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun zipOf(dir: String, m: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in m) { z.putNextEntry(ZipEntry(dir + n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun kitFiles(program: ByteArray = aaLook()) = linkedMapOf(
        "aa-look" to program,
        "aa-look.size" to "${program.size}\n".toByteArray(),
        "install.sh" to "#!/bin/sh\n# install.sh (aa-menu-look 2) - makes the CarLife entry look like Android Auto.\n".toByteArray(),
        "SHA256SUMS" to "${sha(program)}  aa-look\n".toByteArray(),
    )

    private fun png(w: Int, h: Int, type: Int = 6): ByteArray {
        val b = ByteArray(60)
        intArrayOf(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A).forEachIndexed { i, v -> b[i] = v.toByte() }
        b[11] = 13
        "IHDR".toByteArray().copyInto(b, 12)
        for (i in 0..3) { b[16 + i] = (w shr (24 - 8 * i)).toByte(); b[20 + i] = (h shr (24 - 8 * i)).toByte() }
        b[24] = 8; b[25] = type.toByte()
        return b
    }

    @Test
    fun aGoodKitIsTakenFromTheZip() {
        val kit = MenuLookKits.parse(zipOf("aa-menu-look/", kitFiles()))
        assertTrue(kit.problems.toString(), kit.ok)
        assertEquals("aa-menu-look", kit.title)
        assertEquals("2", kit.version)
        assertFalse(kit.known)
        assertTrue(kit.notes.any { it.contains("not identical") })
    }

    @Test
    fun theKnownHashLooksReal() {
        for (h in MenuLookKits.KNOWN.values) assertTrue(Regex("[0-9a-f]{64}").matches(h))
    }

    @Test
    fun aProgramThatIsNotRightIsRefused() {
        assertFalse(MenuLookKits.of(kitFiles(aaLook(qnx = false))).ok)
        assertFalse(MenuLookKits.of(kitFiles(aaLook(machine = 62))).ok)
        assertFalse(MenuLookKits.of(kitFiles(aaLook(usage = false))).ok)
        assertFalse(MenuLookKits.of(mapOf("aa-look" to "#!/bin/sh\necho hi\n".toByteArray())).ok)
        assertFalse(MenuLookKits.of(emptyMap()).ok)
    }

    @Test
    fun aChangedProgramIsFoundByTheChecksumListAndTheSizeFile() {
        val files = kitFiles()
        files["aa-look"] = aaLook().also { it[2000] = 7 }
        val kit = MenuLookKits.of(files)
        assertFalse(kit.ok)
        assertTrue(kit.problems.any { it.contains("SHA256SUMS") })
        val sized = kitFiles().also { it["aa-look.size"] = "123\n".toByteArray() }
        assertTrue(MenuLookKits.of(sized).problems.any { it.contains("aa-look.size") })
    }

    @Test
    fun theMenuLookIsRecognizedFromTheZip() {
        val zip = zipOf("aa-menu-look/", kitFiles() + mapOf("restore.sh" to "#!/bin/sh\n".toByteArray()))
        val r = AddOns.recognize(ZipSurvey.survey("look.zip", zip), zip)
        assertEquals(Match.KNOWN_ADDON, r.match)
        assertEquals("aa-menu-look", r.addOn)
        assertTrue(r.problems.toString(), r.installable)
        assertEquals("Installable now", r.headline)
        assertNotNull(AddOns.menuLookKitOf("look.zip", zip))
        assertNull(AddOns.tileKitOf("look.zip", zip))
        // without the bytes the app only names it
        assertFalse(AddOns.recognize(ZipSurvey.survey("look.zip", zip)).installable)
    }

    @Test
    fun theLabelRules() {
        assertNull(MenuLook.labelProblem("Android Auto"))
        assertNull(MenuLook.labelProblem("AA (Wireless) 2.0"))
        assertNotNull(MenuLook.labelProblem(""))
        assertNotNull(MenuLook.labelProblem(" x"))
        assertNotNull(MenuLook.labelProblem("a".repeat(41)))
        assertNotNull(MenuLook.labelProblem("it's"))
        assertNotNull(MenuLook.labelProblem("a\$b"))
        assertNotNull(MenuLook.labelProblem("Android Auto; rm -rf /"))
        assertNotNull(MenuLook.labelProblem("Café"))
    }

    @Test
    fun thePicturesTheAuthorListsHaveTheirSizes() {
        val bmw = MenuLook.slots("bmw")
        assertEquals(4, bmw.size)
        assertTrue(bmw.any { it.rel == "bmw/id6l/widgetasset/CarLifePad/resources/carlife_selected.png" && it.width == 72 && it.height == 378 })
        assertTrue(bmw.any { it.rel == "common/assetDB/12_baidu/carlife_48x48/carlife_48x48.png" && it.width == 48 })
        assertTrue(MenuLook.slots("bmwi").first().rel.startsWith("bmwi/"))
        assertEquals(4, MenuLook.slots("mini").size)
        assertTrue(MenuLook.slots("toyota").isEmpty())
    }

    @Test
    fun aPictureIsReadAndMeasured() {
        val p = MenuLook.png(png(72, 378))!!
        assertEquals(72, p.width); assertEquals(378, p.height); assertEquals(6, p.colorType)
        assertNull(MenuLook.png("not a png at all, not at all, not at all".toByteArray()))
        assertNull(MenuLook.png(ByteArray(10)))
        val slot = MenuLook.slots("bmw").first()
        assertNull(MenuLook.slotProblem(slot, png(72, 378)))
        assertTrue(MenuLook.slotProblem(slot, png(100, 100))!!.contains("72×378"))
        assertTrue(MenuLook.slotProblem(slot, "jpeg".toByteArray())!!.contains("not a PNG"))
        assertNotNull(MenuLook.slotNote(slot, png(72, 378, type = 2)))
        assertNull(MenuLook.slotNote(slot, png(72, 378, type = 6)))
    }
}
