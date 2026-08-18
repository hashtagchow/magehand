package com.hashtagchow.magehand.core.ddp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The websocket seam. `DdpClient` talks to this, never to OkHttp directly, so the
 * whole protocol machine can be driven by a scripted fake in plain JUnit
 * (docs/design/01-architecture.md: ":core:ddp … plain JUnit tests with a fake
 * websocket; this is the highest-risk module so it gets the most tests").
 */
interface DdpSocket {
    /** Fire-and-forget text frame. Returns false if the socket already gave up. */
    fun send(text: String): Boolean

    fun close(code: Int = 1000, reason: String? = null)
}

/** Callbacks from the socket. Implementations may be called from any thread. */
interface DdpSocketListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int, reason: String?)
    fun onFailure(error: Throwable)
}

/** Opens one socket per DDP session. A new session always means a new socket. */
fun interface DdpSocketFactory {
    fun open(listener: DdpSocketListener): DdpSocket
}

/**
 * Production [DdpSocketFactory]: `wss://<server>/websocket` over OkHttp.
 *
 * OkHttp's own `pingInterval` is left off deliberately — those are RFC-6455 control
 * frames, which prove the TCP path is alive but say nothing about the Meteor session.
 * `DdpClient` runs DDP-level `ping`/`pong` messages instead, which is what the
 * protocol contract in 02-ddp-and-api.md specifies.
 */
class OkHttpDdpSocketFactory(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
    private val headers: Map<String, String> = emptyMap(),
) : DdpSocketFactory {

    override fun open(listener: DdpSocketListener): DdpSocket {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(t)
        })

        return object : DdpSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?) {
                // cancel() rather than close() on abnormal codes: close() waits for the
                // peer's close frame, which a dead server will never send.
                if (code == 1000) socket.close(code, reason) else socket.cancel()
            }
        }
    }

    companion object {
        /**
         * A websocket-tuned view of [base] that **shares its dispatcher, connection
         * pool and cache** — `newBuilder()`, not a fresh `Builder()`.
         *
         * That sharing is the whole point. An `OkHttpClient` owns a thread pool and a
         * connection pool, and neither is reclaimed until an idle timer (~60 s for
         * connections, and the dispatcher's threads outlive that) or an explicit
         * `dispatcher.executorService.shutdown()`. Building one per [DdpClient] — which
         * is one per account switch — stranded a set of both every time the user
         * changed account, because `DdpClient.close()` has no business shutting down a
         * client it did not create. One base client for the process, derived here and
         * for the REST API, means there is nothing to strand.
         *
         * The timeouts are the websocket ones: no read timeout at all, because a DDP
         * connection is idle by design between messages and liveness is proven by
         * `DdpClient`'s own DDP-level ping/pong rather than by the socket timing out.
         */
        fun webSocketClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // websockets are long-lived
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * A standalone websocket client. Convenience for tests and for the
         * `DdpClient.okHttp` default — production goes through [webSocketClient] with
         * the process's one base client (`DataModule`).
         */
        fun defaultClient(): OkHttpClient = webSocketClient(OkHttpClient())
    }
}
