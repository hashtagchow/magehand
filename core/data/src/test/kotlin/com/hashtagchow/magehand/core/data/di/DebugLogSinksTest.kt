package com.hashtagchow.magehand.core.data.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release build's logging is **nothing at all**, and that is an identity claim rather than a
 * behavioural one.
 *
 * `DdpClientConfig.logger` and `WriteQueueConfig.logger` are hot: the write queue calls its sink
 * on every coalesce, every rate-limit retry and every dropped call, building the message string
 * at the call site, and since BUG-16 a `DdpClient` frame line costs a JSON transform and a
 * re-encode of the whole frame. A "release" sink that checked a flag and returned would still
 * pay for all of that, and so — this is the correction the 1.14.2 review made (finding M2) —
 * would a **no-op lambda**, which is what this used to pin: a function that does nothing still
 * has to be *handed* something. So the contract is stricter than "release logs nothing": a
 * disabled sink is `null`, both configs take a nullable sink, and both callers ask for the sink
 * before they build a message. Release pays for no string it will not print.
 *
 * The claim is still an identity one, just about the absence rather than about a shared object.
 *
 * This is also why [DebugLogSinks.sink] takes the enable decision as an argument. A
 * `BuildConfig.DEBUG` read inside it would make the release branch unreachable from here — unit
 * tests compile against the debug variant — and an untestable release path is exactly the thing
 * this pin exists to prevent. The read happens once, at `DataModule`'s two wiring sites.
 */
class DebugLogSinksTest {

    @Test
    fun `a disabled sink is no sink at all`() {
        assertNull(
            "release must wire nothing, not a lambda that decides to do nothing — a no-op is " +
                "still handed a message somebody had to build",
            DebugLogSinks.sink(DebugLogSinks.DDP_TAG, enabled = false),
        )
        assertNull(DebugLogSinks.sink(DebugLogSinks.WRITE_TAG, enabled = false))
    }

    @Test
    fun `an enabled sink is a real one`() {
        val sink = DebugLogSinks.sink(DebugLogSinks.WRITE_TAG, enabled = true)

        assertNotNull(sink)
        // `isReturnDefaultValues` makes `android.util.Log.d` a stub that returns 0 rather than
        // throwing "not mocked", so calling it here proves the sink is invocable off-device.
        sink!!("write: rate limited, retrying adjustQuantity once")
    }

    /**
     * The tags are the whole interface a person has to this feature: `adb logcat -s
     * MageHandDdp` and `adb logcat -s MageHandWrite` are what the next blind repro will type.
     * Renaming one silently would leave the log stream working and the instructions wrong.
     */
    @Test
    fun `the two tags are the ones the repro instructions name`() {
        assertEquals("MageHandDdp", DebugLogSinks.DDP_TAG)
        assertEquals("MageHandWrite", DebugLogSinks.WRITE_TAG)
    }
}
