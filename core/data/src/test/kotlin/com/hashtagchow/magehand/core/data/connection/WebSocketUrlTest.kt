package com.hashtagchow.magehand.core.data.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one piece of string surgery between an account and its socket. A wrong
 * answer here does not fail loudly — it fails as "the character list is empty",
 * which is exactly the bug that is expensive to diagnose.
 */
class WebSocketUrlTest {

    @Test
    fun `https origin becomes the wss websocket endpoint`() {
        assertEquals(
            "wss://dnd.example.com/websocket",
            websocketUrlFor("https://dnd.example.com"),
        )
        assertEquals("wss://dicecloud.com/websocket", websocketUrlFor("https://dicecloud.com"))
    }

    @Test
    fun `a non-default port survives`() {
        assertEquals("wss://dice.example.com:8443/websocket", websocketUrlFor("https://dice.example.com:8443"))
    }

    @Test
    fun `a trailing slash does not produce a double slash`() {
        assertEquals("wss://dicecloud.com/websocket", websocketUrlFor("https://dicecloud.com/"))
    }
}
