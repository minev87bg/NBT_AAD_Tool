package com.nbtaad.editor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of two logins of the head unit the app works on. */
class HeadUnitChoiceTest {
    @Test
    fun theLoginThatHasTheFoldersItselfWins() {
        // the log of the car: .3 sees the folders only through /net/hu-omap/fs/sda0, .10 has them (and /var/opt/nbtapp)
        assertEquals("192.168.0.10", CarDetector.pickByLocalFolders(listOf("192.168.0.3" to 0, "192.168.0.10" to 4)))
        assertEquals("192.168.0.10", CarDetector.pickByLocalFolders(listOf("192.168.0.3" to -1, "192.168.0.10" to 2)))
    }

    @Test
    fun onATieTheFirstStaysAsItWasBefore() {
        assertEquals("192.168.0.3", CarDetector.pickByLocalFolders(listOf("192.168.0.3" to 0, "192.168.0.10" to 0)))
        assertEquals("192.168.0.3", CarDetector.pickByLocalFolders(listOf("192.168.0.3" to -1, "192.168.0.10" to -1)))
        assertNull(CarDetector.pickByLocalFolders(emptyList()))
    }

    @Test
    fun theQuestionNamesTheFoldersOfBothKindsOfInstall() {
        val s = CarDetector.LOCAL_FOLDERS_SCRIPT
        for (d in listOf("/opt/aa", "/opt/hmi/ID5/data/ro/Plugins", "/var/opt/aa", "/var/opt/nbtapp")) assertTrue(d in s)
        assertTrue("\$d" in s)
    }
}
