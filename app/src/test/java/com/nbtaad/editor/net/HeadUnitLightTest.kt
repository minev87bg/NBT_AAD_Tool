package com.nbtaad.editor.net

import com.nbtaad.editor.net.HeadUnitLight.Kind
import com.nbtaad.editor.net.HeadUnitLight.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitLightTest {
    private val qnx = "SSH-2.0-OpenSSH_5.2 QNX_Secure_Shell-20090621-hpn13v6"

    @Test
    fun sshOfTheHeadUnitIsTheGreenLight() {
        val l = HeadUnitLight.judge(listOf(Seen("172.16.224.1", PortState.OPEN, qnx, aaPort = false)), haveNetwork = true)
        assertEquals(Kind.GREEN, l.kind)
        assertEquals("172.16.224.1", l.host)
        assertTrue(l.green)
    }

    @Test
    fun androidAutoAnsweringWhileSshDoesNotIsTheBlockedPacketFilter() {
        // what the phone saw on the car's WiFi with the PF on: only Android Auto's own port answered
        val silent = Seen("172.16.224.1", null, null, aaPort = true)
        val l = HeadUnitLight.judge(listOf(silent), haveNetwork = true)
        assertEquals(Kind.PF_BLOCKED, l.kind)
        assertEquals("172.16.224.1", l.host)
        assertFalse(l.green)
        // a refusal is as much a block as silence
        assertEquals(Kind.PF_BLOCKED, HeadUnitLight.judge(listOf(Seen("172.16.224.1", PortState.REFUSED, null, aaPort = true)), true).kind)
    }

    @Test
    fun theNoticeTellsToRemoveThePfAndRestartAndroidAuto() {
        val l = HeadUnitLight.Light(Kind.PF_BLOCKED, "172.16.224.1")
        assertTrue(HeadUnitLight.headline(l).contains("PF"))
        val steps = HeadUnitLight.PF_STEPS.joinToString(" ")
        assertTrue(steps.contains("remove the PF"))
        assertTrue(steps.contains("Android Auto menu"))
        assertTrue(steps.contains("Restart Android Auto"))
        assertTrue(steps.contains("takes effect"))
        assertNull(HeadUnitLight.advice(l))
    }

    @Test
    fun noAnswerAtAllIsNotBlamedOnThePacketFilter() {
        val l = HeadUnitLight.judge(listOf(Seen("172.16.224.1", null, null, aaPort = false), Seen("172.16.224.100", null, null, aaPort = false)), true)
        assertEquals(Kind.SILENT, l.kind)
        assertTrue(HeadUnitLight.advice(l)!!.contains("Android Auto"))
        assertEquals(Kind.SILENT, HeadUnitLight.judge(emptyList(), true).kind)
    }

    @Test
    fun aRouterThatIsNotTheHeadUnitIsNotGreen() {
        val dropbear = Seen("192.168.1.1", PortState.OPEN, "SSH-2.0-dropbear_2020.81", aaPort = false)
        val l = HeadUnitLight.judge(listOf(dropbear), true)
        assertEquals(Kind.NOT_HEAD_UNIT, l.kind)
        assertTrue(HeadUnitLight.headline(l).contains("dropbear"))
        // an SSH that said nothing is not enough for a green light either
        assertEquals(Kind.NOT_HEAD_UNIT, HeadUnitLight.judge(listOf(Seen("172.16.224.1", PortState.OPEN, null, false)), true).kind)
    }

    @Test
    fun theHeadUnitWinsWhenAnotherAddressAnswersToo() {
        val l = HeadUnitLight.judge(
            listOf(
                Seen("192.168.1.1", PortState.OPEN, "SSH-2.0-dropbear", false),
                Seen("172.16.224.1", PortState.OPEN, qnx, false),
            ),
            true,
        )
        assertEquals(Kind.GREEN, l.kind)
        assertEquals("172.16.224.1", l.host)
    }

    @Test
    fun withoutAnyNetworkThePhoneIsToldToJoinTheCarsWifi() {
        val l = HeadUnitLight.judge(emptyList(), haveNetwork = false)
        assertEquals(Kind.NO_NETWORK, l.kind)
        assertTrue(HeadUnitLight.advice(l)!!.contains("WiFi"))
    }
}
