package com.hashtagchow.magehand.core.data.di

import android.util.Log

/**
 * The two diagnostic sinks the app wires in a debug build, and the no-op it wires in a
 * release one.
 *
 * ### Why this exists at all
 *
 * `DdpClientConfig.logger` and `WriteQueueConfig.logger` both default to `{}`, and nothing had
 * ever overridden them outside a test. That default is right for a library — a log sink with no
 * wire effect is not part of the contract, which is why `ContractExportTest` excludes it — but
 * it meant the two layers that decide what actually reaches the server were **silent on a
 * device**. The inventory-stepper burst bug was reproduced on an emulator with no record of
 * whether an op had been coalesced away, refused, rate-limited or retried; the only evidence
 * available was the number on the screen afterwards. Every one of those four is already a
 * `logger(...)` call in `WriteQueue`, going nowhere.
 *
 * ### Debug only, and the release wiring is *this object's* no-op
 *
 * [sink] takes the enable decision as an argument rather than reading `BuildConfig` itself, so
 * that both branches are reachable from a unit test — a `BuildConfig.DEBUG` read inside here
 * would make the release branch untestable, since unit tests compile against the debug variant.
 * The decision is made once, at the wiring site in `DataModule`, and `DebugLogSinksTest` pins
 * that a disabled sink is [NO_OP] *by identity*: not a lambda that checks a flag and returns,
 * not a lambda that builds a message and drops it, but the same do-nothing function the config
 * defaults would have used. Release therefore pays nothing, including the string concatenation
 * at the call sites.
 */
internal object DebugLogSinks {

    /** `adb logcat -s MageHandDdp` — connection, subscription and method traffic. */
    const val DDP_TAG: String = "MageHandDdp"

    /** `adb logcat -s MageHandWrite` — coalescing, rate-limit retries, dropped calls. */
    const val WRITE_TAG: String = "MageHandWrite"

    /** What a release build gets. Shared, so "release is the no-op" is an identity check. */
    val NO_OP: (String) -> Unit = {}

    /**
     * [Log.d] under [tag] when [enabled], and exactly [NO_OP] when not.
     *
     * @param enabled `BuildConfig.DEBUG` at the wiring site. Passed rather than read here — see
     *   the class KDoc for why that is the testable arrangement.
     */
    fun sink(tag: String, enabled: Boolean): (String) -> Unit =
        if (enabled) { message -> Log.d(tag, message) } else NO_OP
}
