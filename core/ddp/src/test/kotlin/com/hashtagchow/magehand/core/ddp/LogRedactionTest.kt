package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [redactForLog] on its own (BUG-16).
 *
 * `DdpClientTest`'s pins prove the client never hands a sink the token; these prove the rule
 * itself, shape by shape, where the input is written down beside the expected output. The two
 * halves fail differently: a broken rule fails here with a diff, and a rule that stopped being
 * *called* fails there.
 *
 * The token here is a fake with no relationship to the operator's — as everywhere in this suite.
 */
class LogRedactionTest {

    private val frames = Json

    private fun frame(text: String): JsonObject = frames.parseToJsonElement(text) as JsonObject

    /** No call is pending unless a test says so. */
    private val nothingPending: (String) -> String? = { null }

    @Test
    fun `an outbound login frame loses its resume token and keeps everything else`() {
        val logged = redactForLog(
            frame("""{"msg":"method","method":"login","params":[{"resume":"$TOKEN"}],"id":"7"}"""),
            nothingPending,
        )

        assertFalse("the token must not survive: $logged", logged.contains(TOKEN))
        assertTrue("the redaction is visible: $logged", logged.contains("<redacted>"))
        // The frame is still a frame: a repro needs to see which method was called and under
        // which id, and both are diagnostics rather than credentials.
        val back = frame(logged)
        assertEquals("method", (back["msg"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("login", (back["method"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("7", (back["id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("""[{"resume":"<redacted>"}]""", back["params"].toString())
    }

    /**
     * Review L3: the params that were **sent** are scrubbed, key by key, rather than replaced by
     * a constant `resume` object. `login` is Meteor's one method whose arguments are all
     * credentials in some spelling, so no value may survive — but the shape may, and should: a
     * log saying `{"user":"<redacted>","password":"<redacted>"}` says a password login happened,
     * where a constant `resume` would have said something that never occurred.
     */
    @Test
    fun `a login's params are scrubbed key by key, not replaced by a fixed shape`() {
        val logged = redactForLog(
            frame(
                """{"msg":"method","method":"login","id":"7","params":[{"user":{"username":"sabriel"},""" +
                    """"password":"hunter2"}]}"""
            ),
            nothingPending,
        )

        assertFalse("no password: $logged", logged.contains("hunter2"))
        assertFalse("no username either: $logged", logged.contains("sabriel"))
        assertEquals(
            """[{"user":"<redacted>","password":"<redacted>"}]""",
            (frame(logged)["params"]).toString(),
        )
    }

    /**
     * Review NIT-3: the non-object half of the same rule. An entry that is not an object cannot
     * have its keys kept, so it is replaced outright — a bare string sitting in a `login`'s
     * params is a credential in the plainest possible wrapper.
     */
    @Test
    fun `a non-object entry in a login's params is replaced outright`() {
        val logged = redactForLog(
            frame("""{"msg":"method","method":"login","id":"7","params":["$TOKEN"]}"""),
            nothingPending,
        )

        assertFalse("the bare token must not survive: $logged", logged.contains(TOKEN))
        assertEquals("""["<redacted>"]""", frame(logged)["params"].toString())
    }

    // ------------------------------------------------------- error frames (M1)

    /**
     * Review M1. DDP's server→client `error` frame carries an optional `offendingMessage`
     * echoing the frame it is refusing. A refused `login` therefore put the token straight back
     * into the log, one line under the copy that had just been scrubbed out of it — the redaction
     * looked complete and was not.
     *
     * The same rule is applied to the echo, and `reason` survives because `reason` is the whole
     * diagnostic value of an error frame.
     */
    @Test
    fun `an error frame scrubs the login it is refusing and keeps its reason`() {
        val logged = redactForLog(
            frame(
                """{"msg":"error","reason":"Must connect first","offendingMessage":""" +
                    """{"msg":"method","method":"login","params":[{"resume":"$TOKEN"}],"id":"7"}}"""
            ),
            nothingPending,
        )

        assertFalse("the echoed token must not survive: $logged", logged.contains(TOKEN))
        assertTrue("the reason is the point of the frame: $logged", logged.contains("Must connect first"))
        assertTrue("and it is still recognisably the login that was refused", logged.contains("login"))
    }

    /**
     * **Review L2 — fail closed on a shape this client never sends.**
     *
     * The scrub used to return the frame untouched when `params` was not a JSON array, which was
     * safe only while every frame reaching it was one this client had built. M1 broke that: an
     * `error`'s `offendingMessage` is the *server's* echo of what it refused, and a server that
     * spells the refused login's params as an object rather than an array would have walked the
     * token through the one branch that had decided not to look. The `params` key is now
     * replaced whatever its shape.
     */
    @Test
    fun `an echoed login whose params are an object still loses its token`() {
        val logged = redactForLog(
            frame(
                """{"msg":"error","reason":"Bad request","offendingMessage":""" +
                    """{"msg":"method","method":"login","params":{"resume":"$TOKEN"},"id":"7"}}"""
            ),
            nothingPending,
        )

        assertFalse("an object-shaped params must not slip the token through: $logged", logged.contains(TOKEN))
        assertTrue("and the marker says something was taken out: $logged", logged.contains("<redacted>"))
        assertTrue("the reason still survives", logged.contains("Bad request"))
    }

    /** A `login` carrying no `params` at all gains none — the "nothing is added" invariant. */
    @Test
    fun `a login frame with no params gains no redacted one`() {
        val bare = """{"msg":"method","method":"login","id":"7"}"""
        assertEquals(bare, redactForLog(frame(bare), nothingPending))
    }

    /** An error frame that echoes nothing, and one that echoes something harmless. */
    @Test
    fun `an error frame with no login in it is untouched`() {
        val bare = """{"msg":"error","reason":"Bad request"}"""
        assertEquals(bare, redactForLog(frame(bare), nothingPending))

        val other = """{"msg":"error","reason":"Bad request","offendingMessage":""" +
            """{"msg":"method","method":"creatureProperties.damage","params":[{"_id":"p1"}],"id":"9"}}"""
        assertEquals(other, redactForLog(frame(other), nothingPending))
    }

    @Test
    fun `a login result loses its token and keeps the user id and the expiry`() {
        val logged = redactForLog(
            frame(
                """{"msg":"result","id":"7","result":{"id":"user-1","token":"$TOKEN",""" +
                    """"tokenExpires":{"${'$'}date":1786000000000}}}"""
            ),
        ) { id -> if (id == "7") "login" else null }

        assertFalse("the token must not survive: $logged", logged.contains(TOKEN))
        assertTrue(logged.contains(""""token":"<redacted>""""))
        // Kept on purpose: the user id is what makes a frame log worth reading, and it is a
        // docs-zone identifier rather than a credential.
        assertTrue("the user id is kept: $logged", logged.contains(""""id":"user-1""""))
        assertTrue("tokenExpires is kept: $logged", logged.contains("tokenExpires"))
    }

    @Test
    fun `every other frame is encoded as it arrived`() {
        // A method that is not `login` — its params ARE the repro.
        val write = """{"msg":"method","method":"creatureProperties.damage","params":[{"_id":"p1"}],"id":"9"}"""
        assertEquals(write, redactForLog(frame(write), nothingPending))

        // A `result` for a call this client opened for something else. The lookup is what
        // decides, not the frame's shape: a non-login result carrying a field spelled `token`
        // would be the server's own answer and is not ours to rewrite.
        val other = """{"msg":"result","id":"9","result":{"ok":true}}"""
        assertEquals(other, redactForLog(frame(other)) { "creatureProperties.damage" })

        // A `result` whose id resolves to no pending call at all.
        assertEquals(other, redactForLog(frame(other), nothingPending))

        // And a frame that is neither.
        val added = """{"msg":"added","collection":"creatures","id":"c1","fields":{"name":"Dummy"}}"""
        assertEquals(added, redactForLog(frame(added), nothingPending))
    }

    /**
     * The transform never *adds* a field. A `login` result with no `token` in it — which is what
     * `FakeDdpServer.completeHandshake` sends, and what a server answering a password login could
     * send — must not be logged as though it carried a redacted one: that would be this function
     * inventing a claim about the server's answer.
     */
    @Test
    fun `a login result with no token gains no redacted one`() {
        val noToken = """{"msg":"result","id":"7","result":{"id":"user-1"}}"""
        assertEquals(noToken, redactForLog(frame(noToken)) { "login" })
    }

    private companion object {
        const val TOKEN = "FakeResumeToken0FakeResumeToken0"
    }
}
