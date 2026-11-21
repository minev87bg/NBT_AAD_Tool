package com.nbtaad.editor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- the car's own Android Auto WiFi: no car gateway there, the head unit is the router ----

    @Test
    fun theQnxSshOfTheCarIsTheHeadUnitAndARouterIsNot() {
        // what the car answered on 172.16.224.1 (log of the port probe)
        assertTrue(CarDetector.isHeadUnitBanner("SSH-2.0-OpenSSH_5.2 QNX_Secure_Shell-20090621-hpn13v6"))
        assertFalse(CarDetector.isHeadUnitBanner("SSH-2.0-dropbear_2019.78"))
        assertFalse(CarDetector.isHeadUnitBanner("SSH-2.0-OpenSSH_8.9"))
        assertFalse(CarDetector.isHeadUnitBanner(null))
    }

    @Test
    fun theRouterOfThePhonesWifiComesFirstAndThePhoneNeverIs() {
        val nets = listOf(LocalNet("wlan0", "172.16.224.112", 24))
        // from the log: no routers, DHCP server and DNS both 172.16.224.1
        assertEquals(listOf("172.16.224.1"), CarDetector.routerCandidates(nets, listOf("172.16.224.1", "172.16.224.1")))
        // nothing told: the .1 of the network
        assertEquals(listOf("172.16.224.1"), CarDetector.routerCandidates(nets, emptyList()))
        // an address of another network (mobile data DNS, say), the phone itself and rubbish are left out
        assertEquals(
            listOf("172.16.224.254", "172.16.224.1"),
            CarDetector.routerCandidates(nets, listOf("8.8.8.8", "172.16.224.112", "0.0.0.0", "fe80::1", "172.16.224.254")),
        )
    }

    @Test
    fun noMoreCandidatesThanTheLimit() {
        val nets = listOf(LocalNet("wlan0", "10.0.0.5", 24), LocalNet("eth0", "192.168.0.12", 16))
        val c = CarDetector.routerCandidates(nets, listOf("10.0.0.1", "10.0.0.2", "192.168.0.8", "192.168.0.9"), max = 3)
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "192.168.0.8"), c)
    }
}
