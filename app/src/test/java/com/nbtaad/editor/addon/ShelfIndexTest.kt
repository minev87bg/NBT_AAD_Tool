package com.nbtaad.editor.addon

import com.nbtaad.editor.install.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfIndexTest {
    private fun item(id: String, at: Long, size: Long = 1000, installed: Long? = null, name: String = "$id.zip") =
        ShelfItem(id.padEnd(16, '0'), name, at, size, "v1.9.116", "full build", "issue #226, shared by bob", "https://x.test/226", installed)

    @Test
    fun theListSurvivesBeingWrittenAndReadBack() {
        val a = item("a1", 1000, installed = 5000, name = "odd \"name\" \\ with\nnewline.zip")
        val b = ShelfItem("b2".padEnd(16, '0'), "plain.zip", 2000, 55, null, "daemon only", "", null)
        val back = ShelfIndex.parse(ShelfIndex.toJson(listOf(a, b)))
        assertEquals(2, back.size)
        assertEquals(a.name, back[0].name)
        assertEquals(a.id, back[0].id)
        assertEquals(5000L, back[0].installedAt)
        assertEquals("v1.9.116", back[0].version)
        assertEquals("https://x.test/226", back[0].pageUrl)
        assertNull(back[1].version)
        assertNull(back[1].pageUrl)
        assertNull(back[1].installedAt)
        assertEquals("daemon only", back[1].summary)
        assertEquals(55L, back[1].size)
    }

    @Test
    fun anUnreadableOrOddListGivesWhatCanBeRead() {
        assertTrue(ShelfIndex.parse("").isEmpty())
        assertTrue(ShelfIndex.parse("not json").isEmpty())
        assertTrue(ShelfIndex.parse("{\"items\":5}").isEmpty())
        // an entry whose id is not a hash is skipped: the id is used as a file name, so it must be plain hex
        val text = "{\"items\":[{\"id\":\"../../etc/passwd\",\"name\":\"x\"},{\"id\":\"${"c".repeat(16)}\",\"name\":\"ok.zip\"}]}"
        val items = ShelfIndex.parse(text)
        assertEquals(listOf("ok.zip"), items.map { it.name })
    }

    @Test
    fun aNewFileGoesOnTopAndTheOldOnesStay() {
        val (list, dropped) = ShelfIndex.place(listOf(item("a", 1000), item("b", 2000)), item("c", 3000))
        assertEquals(listOf("c", "b", "a").map { it.padEnd(16, '0') }, list.map { it.id })
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun theSameFileAgainIsOneEntryAndKeepsItsInstalledMark() {
        val (list, dropped) = ShelfIndex.place(listOf(item("a", 1000, installed = 1500), item("b", 2000)), item("a", 3000))
        assertEquals(listOf("a", "b").map { it.padEnd(16, '0') }, list.map { it.id })
        assertEquals(3000L, list[0].savedAt)
        assertEquals(1500L, list[0].installedAt)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun theOldestMakeRoomWhenThereAreTooMany() {
        val old = (1..8).map { item("f$it", it * 1000L) }
        val (list, dropped) = ShelfIndex.place(old, item("new", 99_000))
        assertEquals(8, list.size)
        assertEquals("new".padEnd(16, '0'), list[0].id)
        assertEquals(listOf("f1".padEnd(16, '0')), dropped.map { it.id })
        assertFalse(list.any { it.id == dropped[0].id })
    }

    @Test
    fun theOldestMakeRoomWhenTheyAreTooBig() {
        val old = listOf(item("a", 1000, size = 60), item("b", 2000, size = 60))
        val (list, dropped) = ShelfIndex.place(old, item("c", 3000, size = 60), maxItems = 8, maxBytes = 130)
        assertEquals(listOf("c", "b").map { it.padEnd(16, '0') }, list.map { it.id })
        assertEquals(listOf("a".padEnd(16, '0')), dropped.map { it.id })
    }

    @Test
    fun aFileBiggerThanTheLimitIsStillKeptAlone() {
        val (list, dropped) = ShelfIndex.place(listOf(item("a", 1000, size = 10)), item("huge", 2000, size = 500), maxBytes = 100)
        assertEquals(listOf("huge".padEnd(16, '0')), list.map { it.id })
        assertEquals(1, dropped.size)
    }

    @Test
    fun wordsForWhatAFileIs() {
        assertEquals("full build", ShelfIndex.kindText(Kind.BUILD))
        assertEquals("plugin only", ShelfIndex.kindText(Kind.PLUGIN_ONLY))
        assertEquals("daemon only", ShelfIndex.kindText(Kind.DAEMON_ONLY))
    }
}
