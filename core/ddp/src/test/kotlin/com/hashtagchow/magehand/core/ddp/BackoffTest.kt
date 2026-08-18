package com.hashtagchow.magehand.core.ddp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Exponential backoff 1 s → 60 s with jitter (docs/design/01-architecture.md). */
class BackoffTest {

    @Test
    fun schedule_grows_exponentially_and_caps_at_60s() {
        val backoff = ExponentialBackoff(random = Random(1))
        // base for attempt n is 1s * 2^n, jitter ±50%
        val expectedBase = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000)
        expectedBase.forEachIndexed { attempt, base ->
            repeat(50) {
                val delay = backoff.delayMillis(attempt)
                assertTrue("attempt $attempt gave ${delay}ms", delay >= (base * 0.5).toLong() - 1)
                assertTrue("attempt $attempt gave ${delay}ms", delay <= minOf((base * 1.5).toLong(), 60_000L))
            }
        }
    }

    @Test
    fun never_exceeds_the_ceiling_however_long_the_outage() {
        val backoff = ExponentialBackoff()
        for (attempt in 0..500) {
            val delay = backoff.delayMillis(attempt)
            assertTrue("attempt $attempt gave ${delay}ms", delay in 0..60_000)
        }
    }

    @Test
    fun jitter_actually_spreads_the_reconnects() {
        val backoff = ExponentialBackoff(random = Random(42))
        val samples = (1..200).map { backoff.delayMillis(3) }.toSet()
        assertTrue("expected a spread of delays, got ${samples.size} distinct", samples.size > 100)
    }

    @Test
    fun zero_jitter_is_deterministic() {
        val backoff = ExponentialBackoff(jitterRatio = 0.0)
        assertEquals(1_000L, backoff.delayMillis(0))
        assertEquals(4_000L, backoff.delayMillis(2))
        assertEquals(60_000L, backoff.delayMillis(20))
    }

    @Test
    fun negative_attempts_are_clamped() {
        val backoff = ExponentialBackoff(jitterRatio = 0.0)
        assertEquals(1_000L, backoff.delayMillis(-5))
    }
}
