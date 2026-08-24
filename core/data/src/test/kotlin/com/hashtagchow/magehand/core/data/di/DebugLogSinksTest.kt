package com.hashtagchow.magehand.core.data.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The release build's logging is **the no-op**, and that is an identity claim rather than a
 * behavioural one.
 *
 * `DdpClientConfig.logger` and `WriteQueueConfig.logger` are hot: the write queue calls its sink
 * on every coalesce, every rate-limit retry and every dropped call, building the message string
 * at the call site. A "release" sink that checked a flag and returned would still pay for those
 * strings, and a sink that logged at a level release strips would still pay for them twice. So
 * the contract is stricter than "release logs nothing": a disabled sink *is* [DebugLogSinks.NO_OP],
 * the same do-nothing function the two configs default to when nobody wires them at all.
 *
 * This is also why [DebugLogSinks.sink] takes the enable decision as an argument. A
 * `BuildConfig.DEBUG` read inside it would make the release branch unreachable from here — unit
 * tests compile against the debug variant — and an untestable release path is exactly the thing
 * this pin exists to prevent. The read happens once, at `DataModule`'s two wiring sites.
 */
class DebugLogSinksTest {

    @Test
    fun `a disabled sink is the shared no-op, by identity`() {
        assertSame(
            "release must wire the no-op itself, not a lambda that decides to do nothing",
            DebugLogSinks.NO_OP,
            DebugLogSinks.sink(DebugLogSinks.DDP_TAG, enabled = false),
        )
        assertSame(
            DebugLogSinks.NO_OP,
            DebugLogSinks.sink(DebugLogSinks.WRITE_TAG, enabled = false),
        )
    }

    @Test
    fun `an enabled sink is a real one`() {
        val sink = DebugLogSinks.sink(DebugLogSinks.WRITE_TAG, enabled = true)

        assertNotSame(DebugLogSinks.NO_OP, sink)
        // `isReturnDefaultValues` makes `android.util.Log.d` a stub that returns 0 rather than
        // throwing "not mocked", so calling it here proves the sink is invocable off-device.
        sink("write: rate limited, retrying adjustQuantity once")
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
