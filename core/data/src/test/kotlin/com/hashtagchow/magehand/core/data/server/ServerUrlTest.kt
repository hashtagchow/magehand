package com.hashtagchow.magehand.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [normalizeServerUrl] is the app's only gate between "what the user typed" and
 * "the origin we send a password to", so it is tested exhaustively rather than
 * representatively.
 */
class ServerUrlTest {

    private fun normalized(input: String): String {
        val result = normalizeServerUrl(input)
        return (result as? ServerUrlResult.Valid)?.origin
            ?: error("expected <$input> to normalize, got $result")
    }

    private fun problem(input: String): ServerUrlProblem {
        val result = normalizeServerUrl(input)
        return (result as? ServerUrlResult.Invalid)?.problem
            ?: error("expected <$input> to be rejected, got $result")
    }

    // ---- the two forms named in the WP3 brief -------------------------------

    @Test
    fun `bare hostname gains an https scheme`() {
        assertEquals("https://dnd.example.com", normalized("dnd.example.com"))
    }

    @Test
    fun `https url with trailing slash loses the slash`() {
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com/"))
    }

    // ---- scheme handling ----------------------------------------------------

    @Test
    fun `explicit http is rejected rather than silently upgraded`() {
        // Silently upgrading would give the user different security properties
        // than they asked for; refusing tells them the app is https-only.
        assertEquals(ServerUrlProblem.INSECURE_SCHEME, problem("http://dicecloud.com"))
        assertEquals(ServerUrlProblem.INSECURE_SCHEME, problem("HTTP://dicecloud.com"))
        assertEquals(ServerUrlProblem.INSECURE_SCHEME, problem("  http://dnd.example.com/  "))
    }

    @Test
    fun `scheme is matched case-insensitively`() {
        assertEquals("https://dicecloud.com", normalized("HTTPS://DiceCloud.com"))
        assertEquals("https://dicecloud.com", normalized("HtTpS://dicecloud.com/"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertEquals(ServerUrlProblem.UNSUPPORTED_SCHEME, problem("wss://dnd.example.com"))
        assertEquals(ServerUrlProblem.UNSUPPORTED_SCHEME, problem("ftp://dicecloud.com"))
        assertEquals(ServerUrlProblem.UNSUPPORTED_SCHEME, problem("ws://dicecloud.com/websocket"))
        assertEquals(ServerUrlProblem.UNSUPPORTED_SCHEME, problem("javascript:alert(1)"))
        assertEquals(ServerUrlProblem.UNSUPPORTED_SCHEME, problem("mailto:dm@example.com"))
    }

    // ---- path / query / fragment stripping -----------------------------------

    @Test
    fun `paths queries and fragments are stripped`() {
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com/character/abc"))
        assertEquals("https://dicecloud.com", normalized("dicecloud.com/character/abc/edit"))
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com/?next=/home"))
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com#top"))
        assertEquals(
            "https://dnd.example.com",
            normalized("https://dnd.example.com/api/creature/FakeCreature23456"),
        )
    }

    @Test
    fun `repeated trailing slashes are stripped`() {
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com///"))
    }

    // ---- host canonicalisation -----------------------------------------------

    @Test
    fun `host is lower-cased`() {
        assertEquals("https://dnd.example.com", normalized("DND.Example.COM"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://dicecloud.com", normalized("   dicecloud.com \t\n"))
    }

    @Test
    fun `fully qualified trailing dot is dropped`() {
        assertEquals("https://dicecloud.com", normalized("dicecloud.com."))
    }

    @Test
    fun `hyphens and digits in labels are allowed`() {
        assertEquals("https://dnd2.example.com", normalized("dnd2.example.com"))
        assertEquals("https://my-dice-cloud.example.org", normalized("my-dice-cloud.example.org"))
    }

    @Test
    fun `ipv4 literals are accepted`() {
        assertEquals("https://192.0.2.9", normalized("192.0.2.9"))
        assertEquals("https://192.168.0.1:8443", normalized("https://192.168.0.1:8443/"))
    }

    @Test
    fun `localhost is the one accepted single-label host`() {
        assertEquals("https://localhost:3000", normalized("localhost:3000"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dnd"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dicecloud"))
    }

    // ---- ports ----------------------------------------------------------------

    @Test
    fun `non default port is preserved`() {
        assertEquals("https://dnd.example.com:8443", normalized("dnd.example.com:8443"))
        assertEquals("https://dicecloud.com:3000", normalized("https://dicecloud.com:3000/x?y=1"))
    }

    @Test
    fun `default https port is dropped so one server has one spelling`() {
        // Account rows are keyed on (serverUrl, userId); two spellings of the same
        // origin would create a duplicate account.
        assertEquals("https://dicecloud.com", normalized("https://dicecloud.com:443/"))
        assertEquals("https://dicecloud.com", normalized("dicecloud.com:443"))
    }

    @Test
    fun `out of range and empty ports are rejected`() {
        assertEquals(ServerUrlProblem.INVALID_PORT, problem("dicecloud.com:0"))
        assertEquals(ServerUrlProblem.INVALID_PORT, problem("dicecloud.com:65536"))
        assertEquals(ServerUrlProblem.INVALID_PORT, problem("dicecloud.com:99999"))
        assertEquals(ServerUrlProblem.INVALID_PORT, problem("https://dicecloud.com:/path"))
    }

    // ---- rejections -----------------------------------------------------------

    @Test
    fun `empty input is rejected`() {
        assertEquals(ServerUrlProblem.EMPTY, problem(""))
        assertEquals(ServerUrlProblem.EMPTY, problem("    "))
        assertEquals(ServerUrlProblem.EMPTY, problem("\t\n"))
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertEquals(ServerUrlProblem.CREDENTIALS_IN_URL, problem("https://dm:hunter2@dicecloud.com"))
        assertEquals(ServerUrlProblem.CREDENTIALS_IN_URL, problem("dm@dicecloud.com"))
    }

    @Test
    fun `malformed hosts are rejected`() {
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("https://"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("https:///path"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dice cloud.com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dicecloud..com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem(".dicecloud.com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("-dicecloud.com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dicecloud-.com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("dice_cloud.com"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("example.123"))
    }

    @Test
    fun `non ascii hosts are rejected because v1 does no punycode conversion`() {
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("würfelwolke.de"))
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("https://骰子.cn"))
    }

    @Test
    fun `bracketed ipv6 is rejected`() {
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("https://[2001:db8::1]"))
    }

    @Test
    fun `over long hosts and labels are rejected`() {
        val longLabel = "a".repeat(64)
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem("$longLabel.com"))
        val longHost = (1..40).joinToString(".") { "abcdefghij" }
        assertEquals(ServerUrlProblem.MALFORMED_HOST, problem(longHost))
    }

    // ---- idempotence -----------------------------------------------------------

    @Test
    fun `normalizing an already normalized origin is a no-op`() {
        val inputs = listOf(
            "dnd.example.com",
            "https://dicecloud.com/",
            "DICECLOUD.COM:8443/character/x?q=1#f",
            "localhost:3000",
            "192.0.2.9",
        )
        for (input in inputs) {
            val once = normalized(input)
            assertEquals("re-normalizing <$once> changed it", once, normalized(once))
        }
    }

    @Test
    fun `originOrNull exposes the happy path and null otherwise`() {
        assertEquals("https://dicecloud.com", normalizeServerUrl("dicecloud.com").originOrNull())
        assertEquals(null, normalizeServerUrl("http://dicecloud.com").originOrNull())
    }

    @Test
    fun `every problem carries user facing copy`() {
        for (problem in ServerUrlProblem.entries) {
            assert(problem.message.isNotBlank()) { "$problem has no message" }
            assert(problem.message.first().isUpperCase()) { "$problem message is not a sentence" }
        }
    }
}
