package com.hashtagchow.magehand.core.data.di

import android.util.Log

/**
 * The two diagnostic sinks the app wires in a debug build, and the **nothing** it wires in a
 * release one.
 *
 * ### Why this exists at all
 *
 * `DdpClientConfig.logger` and `WriteQueueConfig.logger` both default to "no sink", and nothing
 * had ever overridden them outside a test. That default is right for a library — a log sink with no
 * wire effect is not part of the contract, which is why `ContractExportTest` excludes it — but
 * it meant the two layers that decide what actually reaches the server were **silent on a
 * device**. The inventory-stepper burst bug was reproduced on an emulator with no record of
 * whether an op had been coalesced away, refused, rate-limited or retried; the only evidence
 * available was the number on the screen afterwards. Every one of those four is already a
 * `logger(...)` call in `WriteQueue`, going nowhere.
 *
 * ### Debug only, and the release wiring is **no sink at all**
 *
 * [sink] takes the enable decision as an argument rather than reading `BuildConfig` itself, so
 * that both branches are reachable from a unit test — a `BuildConfig.DEBUG` read inside here
 * would make the release branch untestable, since unit tests compile against the debug variant.
 * The decision is made once, at the wiring site in `DataModule`, and `DebugLogSinksTest` pins
 * that a disabled sink is **`null`**.
 *
 * `null` and not a shared no-op lambda, which is what this returned until the 1.14.2 review
 * (finding M2). A no-op still *receives* a message, so every call site had already built one:
 * `WriteQueue` concatenates an op description on every coalesce and every retry, and since
 * BUG-16 a `DdpClient` frame line costs a JSON transform and a re-encode of the whole frame —
 * paid on every document the server sent, in a release build, and thrown away. Both configs now
 * take a nullable sink and both callers ask for it before they build anything, so the claim
 * "release pays nothing, including the string concatenation at the call sites" is finally true
 * rather than nearly true.
 */
internal object DebugLogSinks {

    /** `adb logcat -s MageHandDdp` — connection, subscription and method traffic. */
    const val DDP_TAG: String = "MageHandDdp"

    /** `adb logcat -s MageHandWrite` — coalescing, rate-limit retries, dropped calls. */
    const val WRITE_TAG: String = "MageHandWrite"

    /**
     * [Log.d] under [tag] when [enabled], and `null` — no sink — when not.
     *
     * @param enabled `BuildConfig.DEBUG` at the wiring site. Passed rather than read here — see
     *   the class KDoc for why that is the testable arrangement.
     */
    fun sink(tag: String, enabled: Boolean): ((String) -> Unit)? =
        if (enabled) { message -> Log.d(tag, message) } else null
}
