package com.generativemascot.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ServerConnectionTest {
    @Test fun connectionRecoveryClearsThePreviousIssueAndReadsNeverRetryPaidWork() = runBlocking {
        var reads = 0
        assertEquals(ServerIssue.UNREACHABLE, readServerIssue({ reads++; throw SocketTimeoutException() }))
        assertNull(readServerIssue({ reads++; 200 }))
        assertEquals(2, reads)
    }
    @Test fun missingHeroIsNotOfflineAndAuthorizationIsReportedSeparately() = runBlocking {
        assertNull(readServerIssue({ 404 }))
        assertEquals(ServerIssue.AUTHORIZATION, readServerIssue({ 401 }))
        assertEquals(ServerIssue.AUTHORIZATION, readServerIssue({ 403 }))
        assertEquals(ServerIssue.SERVER, readServerIssue({ 503 }))
        assertEquals(ServerIssue.TLS, readServerIssue({ throw SSLHandshakeException("test") }))
    }
    @Test fun timeoutIsBoundedAndLeavingTheScreenCancelsTheCheck() = runBlocking {
        assertEquals(ServerIssue.UNREACHABLE, readServerIssue({ delay(5000); 200 }, timeoutMs = 25))
        try {
            readServerIssue({ throw CancellationException("left") })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
