package io.motohub.android.net

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBindFallbackTest {

    // The exact message Android 16 produced on the MTX800 rider's Xiaomi with a lockdown VPN.
    private val refusalMessage = "Binding socket to network 108 failed: EPERM (Operation not permitted)"

    @Test
    fun `recognizes the bind refusal from the exception message`() {
        assertTrue(NetworkBindFallback.isBindRefusal(SocketException(refusalMessage)))
    }

    @Test
    fun `recognizes the bind refusal buried in a cause chain`() {
        val wrapped = IOException("tile download failed", SocketException(refusalMessage))
        assertTrue(NetworkBindFallback.isBindRefusal(wrapped))
    }

    @Test
    fun `ignores ordinary network failures`() {
        assertFalse(NetworkBindFallback.isBindRefusal(SocketTimeoutException("connect timed out")))
        assertFalse(
            NetworkBindFallback.isBindRefusal(
                UnknownHostException("Unable to resolve host \"b.basemaps.cartocdn.com\"")
            )
        )
        assertFalse(NetworkBindFallback.isBindRefusal(SocketException("Connection reset")))
        // EPERM alone, without the bind wording, is not enough to claim a VPN lockdown.
        assertFalse(NetworkBindFallback.isBindRefusal(IOException("EPERM somewhere unrelated")))
    }

    @Test
    fun `survives a self-referential cause chain`() {
        val outer = IOException("outer")
        val inner = IOException("inner", outer)
        outer.initCause(inner)
        assertFalse(NetworkBindFallback.isBindRefusal(outer))
    }
}
