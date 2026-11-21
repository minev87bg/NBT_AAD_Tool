package com.nbtaad.editor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class PortProbeTest {
    /** A port nothing listens on: a listener is opened and closed again. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun aListeningPortIsOpenAndAClosedOneIsRefused() {
        ServerSocket(0).use { server ->
            assertEquals(Answer.OPEN, PortProbe.tcp("127.0.0.1", server.localPort, 500))
        }
        assertEquals(Answer.REFUSED, PortProbe.tcp("127.0.0.1", closedPort(), 500))
    }

    @Test
    fun aScanFindsTheOpenPortAndCountsTheRest() {
        ServerSocket(0).use { server ->
            val closed = (1..5).map { closedPort() }.distinct().filter { it != server.localPort }
            val ports = (closed + server.localPort).toIntArray()
            val scan = PortProbe.scanHost("127.0.0.1", ports, timeoutMs = 500, threads = 4)
            assertEquals(listOf(server.localPort), scan.open)
            assertEquals(closed.size, scan.refused)
            assertEquals(0, scan.silent)
            assertEquals(ports.size, scan.tried)
            assertFalse(scan.stopped)
            assertTrue(PortProbe.reading(scan).contains("refused"))
        }
    }

    @Test
    fun aStoppedScanSaysSoAndTriesNothingMore() {
        val scan = PortProbe.scanHost("127.0.0.1", intArrayOf(closedPort(), closedPort()), threads = 2, cancelled = { true })
        assertTrue(scan.stopped)
        assertEquals(0, scan.tried)
        assertTrue(PortProbe.describe(scan).contains("(stopped)"))
    }

    @Test
    fun theQuickPortsAreThoseUpTo10000AndTheFullOnesAreAll() {
        val quick = PortProbe.portsFor(false)
        assertEquals(1, quick.first())
        assertTrue(quick.contains(22))
        assertTrue(quick.contains(5277))
        assertTrue(quick.contains(52790))
        assertEquals(quick.size, quick.toSet().size)
        val full = PortProbe.portsFor(true)
        assertEquals(65535, full.size)
        assertEquals(1, full.first())
        assertEquals(65535, full.last())
    }

    private fun scan(open: List<Int> = emptyList(), refused: Int = 0, silent: Int = 0, other: Int = 0, firstOther: String? = null) =
        HostScan("192.168.43.1", open, refused, silent, other, firstOther, open.size + refused + silent + other, 100, 5, false)

    @Test
    fun theReadingTellsWhatTheCountsMean() {
        assertTrue(PortProbe.reading(scan(refused = 98, open = listOf(5277))).contains("nothing drops packets"))
        assertTrue(PortProbe.reading(scan(silent = 100)).contains("drops everything"))
        assertTrue(PortProbe.reading(scan(silent = 99, open = listOf(5277))).contains("dropped without an answer"))
        assertTrue(PortProbe.reading(scan(refused = 50, silent = 50)).contains("differently"))
        assertTrue(PortProbe.reading(scan(other = 100, firstOther = "Network is unreachable")).contains("Network is unreachable"))
    }

    @Test
    fun theDescriptionNamesTheOpenPorts() {
        val text = PortProbe.describe(scan(open = listOf(22, 5277), refused = 98))
        assertTrue(text.startsWith("192.168.43.1: open ports: 22, 5277"))
        assertTrue(PortProbe.describe(scan(refused = 100)).contains("open ports: none"))
    }

    @Test
    fun dhcpAddressesComeFromTheLittleEndianNumber() {
        // 192.168.43.1 as DhcpInfo keeps it: the first part in the lowest byte
        val v = (1 shl 24) or (43 shl 16) or (168 shl 8) or 192
        assertEquals("192.168.43.1", PortProbe.leToIp(v))
        assertNull(PortProbe.leToIp(0))
    }

    @Test
    fun theAddressesForTheFullScanAreOrderedAndOnTheNetwork() {
        val got = PortProbe.candidateHosts(
            phoneIps = listOf("192.168.43.12"),
            subnets = listOf("192.168.43"),
            groups = listOf(
                listOf("192.168.43.1", "0.0.0.0"), // router
                listOf("192.168.43.1"), // the same as the DHCP server
                listOf("8.8.8.8", "192.168.43.12"), // a public DNS and the phone itself
                listOf("192.168.43.20", "not an address"),
                listOf("192.168.43.1"),
            ),
        )
        assertEquals(listOf("192.168.43.1", "192.168.43.20"), got)
    }

    @Test
    fun noMoreThanThreeAddressesAreScanned() {
        val many = (1..9).map { "10.0.0.$it" }
        assertEquals(3, PortProbe.candidateHosts(emptyList(), listOf("10.0.0"), listOf(many)).size)
    }

    @Test
    fun aBannerIsMadePrintable() {
        val raw = "SSH-2.0-OpenSSH_6.6\r\n\u0000\u0001".toByteArray(Charsets.ISO_8859_1)
        assertEquals("SSH-2.0-OpenSSH_6.6", PortProbe.printable(raw, raw.size))
        val odd = byteArrayOf(0x01, 'A'.code.toByte(), 0x7f, 'B'.code.toByte())
        assertEquals(".A.B", PortProbe.printable(odd, odd.size))
    }

    @Test
    fun aServiceThatSpeaksFirstShowsItsBanner() {
        ServerSocket(0).use { server ->
            val t = Thread {
                server.accept().use { it.getOutputStream().write("SSH-2.0-test\r\n".toByteArray()) }
            }.apply { isDaemon = true; start() }
            assertTrue(PortProbe.banner("127.0.0.1", server.localPort)!!.startsWith("SSH-2.0-test"))
            t.join(2000)
        }
        assertNull(PortProbe.banner("127.0.0.1", closedPort()))
    }

    @Test
    fun theWholeProbeFindsThePortOnALoopbackNetwork() {
        ServerSocket(0).use { server ->
            val said = ArrayList<String>()
            val text = PortProbe.runWith(
                nets = listOf(LocalNet("test0", "127.0.0.200", 24)),
                facts = NetFacts(listOf("127.0.0.1"), emptyList(), emptyList(), emptyList()),
                deepPorts = intArrayOf(server.localPort, closedPort()),
                say = { synchronized(said) { said += it } },
                cancelled = { false },
            )
            assertTrue(text, text.contains("127.0.0.1: open ports: ${server.localPort}"))
            assertTrue(said.any { it.startsWith("Trying 2 ports of 127.0.0.1") })
        }
    }

    @Test
    fun withoutANetworkTheProbeSaysSo() {
        val text = PortProbe.runWith(emptyList(), NetFacts(emptyList(), emptyList(), emptyList(), emptyList()), intArrayOf(1), {}, { false })
        assertTrue(text.contains("no WiFi network"))
    }
}
