package com.hashtagchow.magehand.core.ddp

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The client-sharing contract.
 *
 * Each [DdpClient] used to default-construct its own `OkHttpClient`, and
 * [DdpClient.close] does not (and should not) shut down a client it was handed — so
 * every account switch stranded a dispatcher thread pool and a connection pool on an
 * idle timer. The fix is structural: one client per process, derived per use.
 * `newBuilder()` is what makes "derived" mean "shares the pools" rather than "copies
 * the settings".
 */
class OkHttpDdpSocketFactoryTest {

    @Test
    fun `a websocket client shares the base client's dispatcher and pools`() {
        val base = OkHttpClient()
        val ws = OkHttpDdpSocketFactory.webSocketClient(base)

        assertSame(base.dispatcher, ws.dispatcher)
        assertSame(base.connectionPool, ws.connectionPool)
        assertSame(base.dispatcher.executorService, ws.dispatcher.executorService)
    }

    @Test
    fun `two websocket clients derived from one base share everything expensive`() {
        val base = OkHttpClient()
        val first = OkHttpDdpSocketFactory.webSocketClient(base)
        val second = OkHttpDdpSocketFactory.webSocketClient(base)

        assertSame(first.dispatcher, second.dispatcher)
        assertSame(first.connectionPool, second.connectionPool)
    }

    @Test
    fun `deriving still applies the websocket timeouts`() {
        val base = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
        val ws = OkHttpDdpSocketFactory.webSocketClient(base)

        // No read timeout: a DDP socket is idle by design between messages, and
        // liveness is proven by DDP-level ping/pong instead.
        assertEquals(0, ws.readTimeoutMillis)
        assertEquals(15_000, ws.connectTimeoutMillis)
    }

    /**
     * The negative control, and the shape of the original bug: two *independently
     * built* clients share nothing, so one per account switch is one leak per switch.
     */
    @Test
    fun `independently built clients share nothing — which is what made this a leak`() {
        assertNotSame(OkHttpClient().connectionPool, OkHttpClient().connectionPool)
        assertNotSame(
            OkHttpDdpSocketFactory.defaultClient().dispatcher,
            OkHttpDdpSocketFactory.defaultClient().dispatcher,
        )
    }
}
