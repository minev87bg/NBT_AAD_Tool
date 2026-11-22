package com.nbtaad.editor.addon

import com.nbtaad.editor.install.AssetInfo
import com.nbtaad.editor.install.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved add-ons and how the newest release of one is found among a project's releases. */
class AddOnCatalogTest {
    private val tile = AddOnCatalog.ENTRIES.first { it.id == "nbt-aa-tile" }
    private val look = AddOnCatalog.ENTRIES.first { it.id == "aa-menu-look" }

    private fun asset(name: String) = AssetInfo(name, 1000L, "https://example.invalid/$name", null)
    private fun release(tag: String, published: String, vararg files: String, pre: Boolean = false) =
        ReleaseInfo(tag, tag, pre, published, "notes of $tag", "https://example.invalid/r/$tag", files.map(::asset))

    @Test
    fun theFollowedProjectIsDeathwalker80sAndIsListedOnce() {
        assertEquals(listOf("deathwalker80/nbt-aa-tile"), AddOnCatalog.PROJECTS.map { it.name })
        val p = AddOnCatalog.DEATHWALKER80
        assertEquals("https://api.github.com/repos/deathwalker80/nbt-aa-tile/releases?per_page=30", p.listUrl)
        assertEquals("https://github.com/deathwalker80/nbt-aa-tile/releases", p.releasesUrl)
    }

    @Test
    fun theVersionIsTheNumberInTheZipsName() {
        assertEquals("5", AddOnCatalog.versionOf(tile, "nbt-aa-tile-5.zip"))
        assertEquals("12", AddOnCatalog.versionOf(tile, "NBT-AA-TILE-12.ZIP"))
        assertEquals("5", AddOnCatalog.versionOf(tile, "nbt-aa-tile-5 (1).zip"))
        assertEquals("2", AddOnCatalog.versionOf(look, "aa-menu-look-2.zip"))
        assertNull(AddOnCatalog.versionOf(tile, "aa-menu-look-2.zip"))
        assertNull(AddOnCatalog.versionOf(tile, "nbt-aa-tile-5.tar.gz"))
        assertNull(AddOnCatalog.versionOf(tile, "SHA256SUMS"))
    }

    @Test
    fun oneReleaseCanCarryBothAddOns() {
        val r = release("v5", "2026-11-09T10:00:00Z", "nbt-aa-tile-5.zip", "aa-menu-look-2.zip", "SHA256SUMS")
        val t = AddOnCatalog.newest(tile, listOf(r))!!
        val l = AddOnCatalog.newest(look, listOf(r))!!
        assertEquals("nbt-aa-tile-5.zip", t.asset.name); assertEquals("5", t.version)
        assertEquals("aa-menu-look-2.zip", l.asset.name); assertEquals("2", l.version)
        assertEquals("v5", t.release.tag)
    }

    @Test
    fun eachAddOnTakesTheNewestReleaseThatHasItsZip() {
        val rs = listOf(
            release("v6", "2026-12-01T10:00:00Z", "nbt-aa-tile-6.zip"),
            release("v5", "2026-11-09T10:00:00Z", "nbt-aa-tile-5.zip", "aa-menu-look-2.zip"),
        )
        assertEquals("v6", AddOnCatalog.newest(tile, rs)!!.release.tag)
        assertEquals("v5", AddOnCatalog.newest(look, rs)!!.release.tag)
    }

    @Test
    fun theOrderOfTheListDoesNotMatterAndAFullReleaseBeatsAPreRelease() {
        val rs = listOf(
            release("v5", "2026-11-09T10:00:00Z", "nbt-aa-tile-5.zip"),
            release("v7-beta", "2026-12-20T10:00:00Z", "nbt-aa-tile-7.zip", pre = true),
            release("v6", "2026-12-01T10:00:00Z", "nbt-aa-tile-6.zip"),
        )
        assertEquals("v6", AddOnCatalog.newest(tile, rs)!!.release.tag)
        val onlyPre = listOf(release("v7-beta", "2026-12-20T10:00:00Z", "nbt-aa-tile-7.zip", pre = true))
        val f = AddOnCatalog.newest(tile, onlyPre)!!
        assertEquals("7", f.version)
        assertTrue(f.release.prerelease)
    }

    @Test
    fun noZipNoResult() {
        assertNull(AddOnCatalog.newest(tile, emptyList()))
        assertNull(AddOnCatalog.newest(tile, listOf(release("v1", "2026-01-01T00:00:00Z", "something.zip", "notes.txt"))))
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(AddOnCatalog.isNewer("10", "9"))
        assertFalse(AddOnCatalog.isNewer("5", "5"))
        assertFalse(AddOnCatalog.isNewer("4", "5"))
        assertTrue(AddOnCatalog.isNewer("5b", "5"))
        assertEquals("6", AddOnCatalog.newestKept(tile, listOf("nbt-aa-tile-5.zip", "nbt-aa-tile-6.zip", "aa-menu-look-9.zip", "x.zip")))
        assertNull(AddOnCatalog.newestKept(tile, listOf("aa-menu-look-9.zip")))
    }

    @Test
    fun theCardOffersWhatIsPossible() {
        val c = AddOnCatalog
        assertEquals(AddOnCatalog.Do.GET, c.plan("5", null, checking = false, offline = false).doing)
        assertEquals("GET 5 AND OPEN", c.plan("5", null, false, false).label)
        assertEquals(AddOnCatalog.Do.UPDATE, c.plan("6", "5", false, false).doing)
        assertEquals(AddOnCatalog.Do.OPEN_SAVED, c.plan("5", "5", false, false).doing)
        assertEquals(AddOnCatalog.Do.OPEN_SAVED, c.plan("5", "7", false, false).doing)
        // no answer from GitHub (yet, or at all): a copy on the phone still opens
        assertEquals(AddOnCatalog.Do.OPEN_SAVED, c.plan(null, "5", checking = true, offline = false).doing)
        assertEquals(AddOnCatalog.Do.OPEN_SAVED, c.plan(null, "5", checking = false, offline = true).doing)
        assertEquals(AddOnCatalog.Do.WAIT, c.plan(null, null, true, false).doing)
        assertEquals(AddOnCatalog.Do.NOTHING, c.plan(null, null, false, true).doing)
        assertTrue(c.plan(null, null, false, true).status.contains("no copy"))
        assertEquals(AddOnCatalog.Do.NOTHING, c.plan(null, null, false, false).doing)
    }

    @Test
    fun theCutNeverLeavesALinkOrCodeOpen() {
        val inLink = "Start here and read on. [issue #55](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto/issues/55) and more words after it"
        val d = AddOnCatalog.notesDigest(inLink, 50)
        assertEquals("Start here and read on. …", d)
        val whole = "See [issue #55](https://x.y/55) for the details of what changed in this build and more"
        assertTrue(AddOnCatalog.notesDigest(whole, 40).contains("[issue #55](https://x.y/55)"))
        assertEquals(-1, AddOnCatalog.openMarkup("a [b](https://c.d) and `e` and **f**"))
        assertEquals(4, AddOnCatalog.openMarkup("one `code that goes on"))
        assertEquals(4, AddOnCatalog.openMarkup("one **bold that goes on"))
        assertEquals(4, AddOnCatalog.openMarkup("one ![a pic](https://c.d/"))
    }

    @Test
    fun notesAreCutShortAtAWordOrLine() {
        assertEquals("Short note.", AddOnCatalog.notesDigest("  Short note.\r\n"))
        val long = "First line.\n" + "word ".repeat(200)
        val d = AddOnCatalog.notesDigest(long, 100)
        assertTrue(d.length <= 104)
        assertTrue(d.endsWith("…"))
        assertTrue(d.startsWith("First line."))
        assertNotNull(AddOnCatalog.ENTRIES.all { it.summary.isNotBlank() && it.author.isNotBlank() })
    }
}
