package com.hashtagchow.magehand.core.data.connection

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.DdpSocket
import com.hashtagchow.magehand.core.model.Account

/**
 * One `OkHttpClient` for every account this process ever connects to.
 *
 * `DefaultDdpConnectionManager` builds a fresh [DdpClient] on every account switch and
 * closes the previous one. When the factory default-constructed an `OkHttpClient` per
 * call, each switch left a dispatcher thread pool and a connection pool behind —
 * `DdpClient.close()` will not shut down a client it does not own, and rightly so.
 * This pins the other half of that contract: the client is shared, so there is never
 * one to reclaim.
 */
class SharedClientFactoryTest {

    /** Opens nothing. The factory is only asked which client it was given. */
    private val silentSocket = object : DdpSocket {
        override fun send(text: String): Boolean = false
        override fun close(code: Int, reason: String?) = Unit
    }

    private fun account(id: String, server: String) = Account(
        id = id,
        serverUrl = server,
        userId = "user-$id",
        username = "user-$id",
        addedAt = 0L,
        lastUsedAt = 0L,
    )

    @Test
    fun `every account is handed the one injected OkHttp client`() {
        val shared = OkHttpClient()
        val handed = mutableListOf<OkHttpClient>()
        val built = mutableListOf<DdpClient>()

        val factory = DefaultDdpConnectionManager.sharedClientFactory(
            httpClient = shared,
            build = { _, client, _ ->
                handed += client
                DdpClient(socketFactory = { silentSocket }).also { built += it }
            },
        )

        try {
            factory(account("a", "https://dicecloud.com")) { null }
            factory(account("b", "https://sheets.example.com")) { null }

            assertEquals(2, handed.size)
            assertSame("the first account got a client of its own", shared, handed[0])
            assertSame("the second account got a client of its own", shared, handed[1])
        } finally {
            built.forEach { it.close() }
        }
    }

    @Test
    fun `each account gets its own websocket url`() {
        val urls = mutableListOf<String>()
        val built = mutableListOf<DdpClient>()

        val factory = DefaultDdpConnectionManager.sharedClientFactory(
            httpClient = OkHttpClient(),
            build = { url, _, _ ->
                urls += url
                DdpClient(socketFactory = { silentSocket }).also { built += it }
            },
        )

        try {
            factory(account("a", "https://dicecloud.com")) { null }
            factory(account("b", "https://sheets.example.com")) { null }

            assertEquals(
                listOf("wss://dicecloud.com/websocket", "wss://sheets.example.com/websocket"),
                urls,
            )
        } finally {
            built.forEach { it.close() }
        }
    }
}
