package com.nbtaad.editor.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseHostPortTest {
    @Test
    fun aPlainAddressGetsTheDefaultPort() {
        assertEquals("10.76.88.65" to 22, parseHostPort("10.76.88.65"))
        assertEquals("10.76.88.65" to 22, parseHostPort(" 10.76.88.65 "))
    }

    @Test
    fun aColonCarriesItsOwnPort() {
        assertEquals("10.76.88.65" to 2222, parseHostPort("10.76.88.65:2222"))
        assertEquals("10.76.88.65" to 2222, parseHostPort(" 10.76.88.65:2222 "))
    }

    @Test
    fun aCustomDefaultPortIsUsedWhenThereIsNoColon() {
        assertEquals("172.16.222.97" to 6801, parseHostPort("172.16.222.97", defaultPort = 6801))
    }

    @Test
    fun aBadOrOutOfRangePortIsDroppedInsteadOfPoisoningTheHost() {
        // this is the exact shape that used to reach JSch as a single malformed host and throw
        // UnknownHostException("10.76.88.65:6801") - the port after the colon must never survive into the host.
        assertEquals("10.76.88.65" to 22, parseHostPort("10.76.88.65:6801zz"))
        assertEquals("10.76.88.65" to 22, parseHostPort("10.76.88.65:"))
        assertEquals("10.76.88.65" to 22, parseHostPort("10.76.88.65:0"))
        assertEquals("10.76.88.65" to 22, parseHostPort("10.76.88.65:99999"))
    }
}
