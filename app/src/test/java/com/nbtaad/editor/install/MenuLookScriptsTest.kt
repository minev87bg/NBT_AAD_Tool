package com.nbtaad.editor.install

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The menu look job, run with this machine's sh on a folder made like the head unit and a stand-in for aa-look. */
class MenuLookScriptsTest {
    private lateinit var root: File
    private val fake = """
        #!/bin/sh
        case "${'$'}1" in
          png) read l < "${'$'}2"; case "${'$'}l" in PNG*) set -- ${'$'}l; echo "${'$'}2 ${'$'}3 ${'$'}4"; exit 0;; *) exit 1;; esac ;;
          same) cmp -s "${'$'}2" "${'$'}3"; exit ${'$'}? ;;
          labels) if grep -q "CarLife" "${'$'}3"; then sed "s/CarLife/${'$'}5/g" "${'$'}3" > "${'$'}4"; echo "OK: fake labels"; exit 0; else exit 3; fi ;;
        esac
        exit 9
    """.trimIndent() + "\n"
    private val tilePic = "bmw/id6l/widgetasset/CarLifePad/resources/carlife_inactive.png"

    @Before
    fun makeRoot() {
        assumeTrue(File("/bin/sh").canExecute())
        RemoteScripts.pathFix = ""
        root = File.createTempFile("nbt-aad-look", "").also { it.delete() }
        val ro = File(root, "opt/hmi/ID5/data/ro")
        File(ro, "bmw/db").mkdirs()
        File(ro, tilePic).parentFile.mkdirs()
        File(root, "dev/shmem").mkdirs(); File(root, "stage").mkdirs(); File(root, "opt/aa").mkdirs()
        File(ro, "bmw/db/text.db").writeText("menu\nCarLife\nend\n")
        File(ro, tilePic).writeText("PNG 72 378 6\noriginal\n")
        File(root, "stage/aa-look").writeText(fake)
        File(root, "stage/img0.png").writeText("PNG 72 378 6\nnew picture\n")
        File(root, "stage/img1.png").writeText("PNG 10 10 6\nwrong\n")
    }

    @After
    fun clean() {
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private val text get() = File(root, "opt/hmi/ID5/data/ro/bmw/db/text.db")
    private val pic get() = File(root, "opt/hmi/ID5/data/ro/$tilePic")

    private fun run(
        action: MenuLookScripts.Action,
        label: String = "Android Auto",
        images: List<MenuLookScripts.Image> = emptyList(),
        brand: String = "bmw",
    ): TileScripts.Report {
        val job = MenuLookScripts.job(action, brand, label, images, fake.length, root.path, File(root, "stage").path)
        val f = File(root, "stage/job.sh")
        f.writeText(job)
        ProcessBuilder("/bin/sh", f.path).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start().waitFor()
        return TileScripts.parse(File(root, "stage/out").takeIf { it.exists() }?.readText() ?: "NOREPORT")
    }

    private val img0 = listOf(MenuLookScripts.Image("img0.png", "bmw/id6l/widgetasset/CarLifePad/resources/carlife_inactive.png"))

    @Test
    fun lookingSaysWhichBrandsAreThereAndChangesNothing() {
        val r = run(MenuLookScripts.Action.LOOK)
        assertTrue(r.success)
        assertEquals("yes", r.state["brand-bmw"])
        assertEquals("no", r.state["installed"])
        assertEquals("menu\nCarLife\nend\n", text.readText())
    }

    @Test
    fun checkingMakesTheLabelInRamAndChangesNothingOnTheHeadUnit() {
        val r = run(MenuLookScripts.Action.CHECK, images = img0)
        assertTrue(r.error ?: "", r.success)
        assertEquals("1", r.state["labels"])
        assertEquals("1", r.state["pictures"])
        assertTrue(r.log.contains("fake labels"))
        assertEquals("menu\nCarLife\nend\n", text.readText())
        assertEquals("PNG 72 378 6\noriginal\n", pic.readText())
        assertFalse(File(root, "opt/aa/menu-look-backup").exists())
        assertEquals(emptyList<String>(), File(root, "dev/shmem").list()!!.toList())
    }

    @Test
    fun applyingKeepsTheFactoryCopiesAndRestoringPutsThemBack() {
        val a = run(MenuLookScripts.Action.APPLY, images = img0)
        assertTrue(a.error ?: "", a.success)
        assertEquals("menu\nAndroid Auto\nend\n", text.readText())
        assertEquals("PNG 72 378 6\nnew picture\n", pic.readText())
        val b = File(root, "opt/aa/menu-look-backup")
        assertEquals("menu\nCarLife\nend\n", File(b, "ro/bmw/db/text.db").readText())
        assertEquals("PNG 72 378 6\noriginal\n", File(b, "ro/$tilePic").readText())
        assertEquals("bmw", File(b, "brand").readText().trim())
        assertEquals(listOf("bmw/db/text.db", tilePic), File(b, "files.txt").readLines())
        assertEquals(emptyList<String>(), File(root, "dev/shmem").list()!!.toList())

        val seen = run(MenuLookScripts.Action.LOOK)
        assertEquals("yes", seen.state["installed"])
        assertEquals("2", seen.state["files"])

        val r = run(MenuLookScripts.Action.RESTORE)
        assertTrue(r.error ?: "", r.success)
        assertEquals("menu\nCarLife\nend\n", text.readText())
        assertEquals("PNG 72 378 6\noriginal\n", pic.readText())
        assertFalse(b.exists())
    }

    @Test
    fun applyingAgainWithAnotherLabelStartsFromTheFactoryCopy() {
        run(MenuLookScripts.Action.APPLY)
        assertEquals("menu\nAndroid Auto\nend\n", text.readText())
        val r = run(MenuLookScripts.Action.APPLY, label = "My AA")
        assertTrue(r.error ?: "", r.success)
        assertEquals("menu\nMy AA\nend\n", text.readText())
        // the factory copy is still the first one, not the earlier result
        assertEquals("menu\nCarLife\nend\n", File(root, "opt/aa/menu-look-backup/ro/bmw/db/text.db").readText())
    }

    @Test
    fun aPictureOfTheWrongSizeChangesNothing() {
        val r = run(MenuLookScripts.Action.APPLY, images = listOf(MenuLookScripts.Image("img1.png", tilePic)))
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("10x10"))
        assertEquals("menu\nCarLife\nend\n", text.readText())
        assertEquals("PNG 72 378 6\noriginal\n", pic.readText())
        assertFalse(File(root, "opt/aa/menu-look-backup").exists())
    }

    @Test
    fun aPictureForAFileThatIsNotThereIsSkipped() {
        val r = run(MenuLookScripts.Action.CHECK, images = listOf(MenuLookScripts.Image("img0.png", "bmw/id6l/widgetasset/CarLifePad/resources/carlife_touch.png")))
        assertTrue(r.error ?: "", r.success)
        assertEquals("0", r.state["pictures"])
        assertTrue(r.info.any { it.contains("skipped") })
    }

    @Test
    fun aBrandThatIsNotOnTheHeadUnitIsRefused() {
        val r = run(MenuLookScripts.Action.CHECK, brand = "mini")
        assertNotNull(r.error)
        assertEquals("menu\nCarLife\nend\n", text.readText())
    }

    @Test
    fun anOlderMenuKitIsNotBuiltOver() {
        File(root, "opt/aa/menu-prima").mkdirs()
        val r = run(MenuLookScripts.Action.APPLY)
        assertNotNull(r.error)
        assertEquals("yes", r.state["oldkit"])
        assertEquals("menu\nCarLife\nend\n", text.readText())
    }

    @Test
    fun restoringWithNothingAppliedIsHarmless() {
        val r = run(MenuLookScripts.Action.RESTORE)
        assertTrue(r.error ?: "", r.success)
        assertEquals("menu\nCarLife\nend\n", text.readText())
    }

    @Test
    fun theJobRefusesWhatCouldBreakTheShell() {
        for (bad in listOf("it's", "a\$b", "x; rm -rf /", "")) {
            try {
                MenuLookScripts.job(MenuLookScripts.Action.APPLY, "bmw", bad)
                throw AssertionError("accepted label '$bad'")
            } catch (_: IllegalArgumentException) {}
        }
        try {
            MenuLookScripts.job(MenuLookScripts.Action.APPLY, "toyota", "Android Auto")
            throw AssertionError("accepted brand")
        } catch (_: IllegalArgumentException) {}
        try {
            MenuLookScripts.job(MenuLookScripts.Action.APPLY, "bmw", "Android Auto", listOf(MenuLookScripts.Image("img0.png", "../etc/passwd.png")))
            throw AssertionError("accepted a path with ..")
        } catch (_: IllegalArgumentException) {}
    }
}
