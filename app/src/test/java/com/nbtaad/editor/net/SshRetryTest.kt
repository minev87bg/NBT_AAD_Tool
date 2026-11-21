package com.nbtaad.editor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The log of 2026-11-21: a login that came right after two others was answered with "Auth fail", the next one a while later worked. */
class SshRetryTest {
    private val authFail = "Auth fail for methods 'publickey,password,keyboard-interactive'"

    @Test
    fun anAuthFailIsTriedAgainAfterAPauseThatGrows() {
        assertEquals(4000L, SshSession.authRetryDelayMs(authFail, 0))
        assertEquals(8000L, SshSession.authRetryDelayMs(authFail, 1))
    }

    @Test
    fun itGivesUpAfterTheRetriesAndForOtherFailures() {
        assertNull(SshSession.authRetryDelayMs(authFail, SshSession.AUTH_RETRIES))
        assertNull(SshSession.authRetryDelayMs("Connection refused", 0))
        assertNull(SshSession.authRetryDelayMs("timeout: socket is not established", 0))
        assertNull(SshSession.authRetryDelayMs(null, 0))
    }
}
