package com.hashtagchow.magehand.core.ddp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** How long to wait before reconnect attempt number [attempt] (0-based). */
fun interface BackoffPolicy {
    fun delayMillis(attempt: Int): Long
}

/**
 * Exponential backoff 1 s → 60 s with jitter
 * (docs/design/01-architecture.md `DdpConnection`, 06-offline-and-sync.md).
 *
 * Attempt *n* has base delay `initial * factor^n`, clamped to [maxMillis], then
 * spread uniformly over `base ± base*jitterRatio` (also clamped). With the defaults
 * that is roughly 0.5–1.5 s, 1–3 s, 2–6 s, … 30–60 s — the jitter matters because
 * the whole table reconnects at once when the venue's wifi hiccups.
 */
class ExponentialBackoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val factor: Double = 2.0,
    private val jitterRatio: Double = 0.5,
    private val random: Random = Random.Default,
) : BackoffPolicy {

    init {
        require(initialMillis > 0) { "initialMillis must be > 0" }
        require(maxMillis >= initialMillis) { "maxMillis must be >= initialMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0..1" }
    }

    override fun delayMillis(attempt: Int): Long {
        val exp = attempt.coerceAtLeast(0).coerceAtMost(MAX_EXPONENT)
        val base = min(initialMillis.toDouble() * factor.pow(exp), maxMillis.toDouble())
        val spread = base * jitterRatio
        val lo = max(0.0, base - spread)
        val hi = min(maxMillis.toDouble(), base + spread)
        if (hi <= lo) return lo.toLong()
        return (lo + random.nextDouble() * (hi - lo)).toLong()
    }

    private companion object {
        /** factor^63 already overflows any sane clamp; stops pow() reaching Infinity. */
        const val MAX_EXPONENT = 63
    }
}

/** No waiting at all — for tests that drive reconnects. */
object NoBackoff : BackoffPolicy {
    override fun delayMillis(attempt: Int): Long = 0
}
