package com.tengus.resilience

import com.tengus.config.BackoffType
import com.tengus.config.RetryConfig
import com.tengus.config.RetryStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class RetryPolicyResolverTest : FunSpec({

    // --- Requirement 22.2: resolve returns site-specific strategy when configured ---

    test("resolve returns site-specific strategy when configured") {
        val siteStrategy = RetryStrategy(
            maxRetries = 5,
            backoffType = BackoffType.LINEAR,
            baseDelayMs = 200L,
            jitterRangeMs = 0L..50L
        )
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.EXPONENTIAL,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L,
            perSiteOverrides = mapOf("site-a" to siteStrategy)
        )
        val resolver = RetryPolicyResolver(config)

        val resolved = resolver.resolve("site-a")
        resolved shouldBe siteStrategy
    }

    // --- Requirement 22.3: resolve returns global fallback when no site override ---

    test("resolve returns global fallback when no site override") {
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.EXPONENTIAL,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L,
            perSiteOverrides = mapOf(
                "site-a" to RetryStrategy(5, BackoffType.LINEAR, 200L, 0L..50L)
            )
        )
        val resolver = RetryPolicyResolver(config)

        val resolved = resolver.resolve("unknown-site")
        resolved.maxRetries shouldBe 3
        resolved.backoffType shouldBe BackoffType.EXPONENTIAL
        resolved.baseDelayMs shouldBe 1000L
        resolved.jitterRangeMs shouldBe 0L..100L
    }

    // --- Requirement 22.4: computeDelay FIXED: baseDelayMs + jitter ---

    test("computeDelay FIXED returns baseDelayMs plus jitter") {
        val seededRandom = Random(42)
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.FIXED,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L
        )
        val resolver = RetryPolicyResolver(config, seededRandom)

        val strategy = RetryStrategy(
            maxRetries = 3,
            backoffType = BackoffType.FIXED,
            baseDelayMs = 500L,
            jitterRangeMs = 10L..20L
        )

        val delay1 = resolver.computeDelay(strategy, 1)
        val delay2 = resolver.computeDelay(strategy, 3)

        // FIXED: base is always 500, regardless of attempt
        // delay = 500 + jitter where jitter in [10, 20]
        delay1 shouldBeGreaterThanOrEqual 510L
        delay1 shouldBeLessThanOrEqual 520L
        delay2 shouldBeGreaterThanOrEqual 510L
        delay2 shouldBeLessThanOrEqual 520L
    }

    // --- Requirement 22.4: computeDelay LINEAR: baseDelayMs * attempt + jitter ---

    test("computeDelay LINEAR returns baseDelayMs times attempt plus jitter") {
        val seededRandom = Random(42)
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.LINEAR,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L
        )
        val resolver = RetryPolicyResolver(config, seededRandom)

        val strategy = RetryStrategy(
            maxRetries = 3,
            backoffType = BackoffType.LINEAR,
            baseDelayMs = 100L,
            jitterRangeMs = 0L..0L // zero jitter for predictable assertion
        )

        // LINEAR: base * attempt + jitter(0)
        resolver.computeDelay(strategy, 1) shouldBe 100L  // 100 * 1
        resolver.computeDelay(strategy, 2) shouldBe 200L  // 100 * 2
        resolver.computeDelay(strategy, 3) shouldBe 300L  // 100 * 3
    }

    // --- Requirement 22.4: computeDelay EXPONENTIAL: baseDelayMs * 2^(attempt-1) + jitter ---

    test("computeDelay EXPONENTIAL returns baseDelayMs times 2^(attempt-1) plus jitter") {
        val seededRandom = Random(42)
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.EXPONENTIAL,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L
        )
        val resolver = RetryPolicyResolver(config, seededRandom)

        val strategy = RetryStrategy(
            maxRetries = 5,
            backoffType = BackoffType.EXPONENTIAL,
            baseDelayMs = 100L,
            jitterRangeMs = 0L..0L // zero jitter for predictable assertion
        )

        // EXPONENTIAL: base * 2^(attempt-1) + jitter(0)
        resolver.computeDelay(strategy, 1) shouldBe 100L   // 100 * 2^0 = 100
        resolver.computeDelay(strategy, 2) shouldBe 200L   // 100 * 2^1 = 200
        resolver.computeDelay(strategy, 3) shouldBe 400L   // 100 * 2^2 = 400
        resolver.computeDelay(strategy, 4) shouldBe 800L   // 100 * 2^3 = 800
    }

    // --- Requirement 22.5: Jitter is within configured range ---

    test("jitter is within configured range") {
        val seededRandom = Random(123)
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.FIXED,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L
        )
        val resolver = RetryPolicyResolver(config, seededRandom)

        val strategy = RetryStrategy(
            maxRetries = 3,
            backoffType = BackoffType.FIXED,
            baseDelayMs = 1000L,
            jitterRangeMs = 50L..150L
        )

        // Run multiple times to verify jitter stays in range
        repeat(50) {
            val delay = resolver.computeDelay(strategy, 1)
            // delay = 1000 + jitter where jitter in [50, 150]
            delay shouldBeGreaterThanOrEqual 1050L
            delay shouldBeLessThanOrEqual 1150L
        }
    }

    // --- Requirement 22.5: Validation rejects negative maxRetries ---

    test("validation rejects negative maxRetries") {
        val badStrategy = RetryStrategy(
            maxRetries = -1,
            backoffType = BackoffType.FIXED,
            baseDelayMs = 100L,
            jitterRangeMs = 0L..10L
        )
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.FIXED,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L,
            perSiteOverrides = mapOf("bad-site" to badStrategy)
        )

        val exception = shouldThrow<IllegalArgumentException> {
            RetryPolicyResolver(config)
        }
        exception.message shouldBe "Invalid retry config for site 'bad-site': maxRetries must be >= 0, got -1"
    }

    // --- Requirement 22.5: Validation rejects non-positive baseDelayMs ---

    test("validation rejects non-positive baseDelayMs") {
        val badStrategy = RetryStrategy(
            maxRetries = 3,
            backoffType = BackoffType.EXPONENTIAL,
            baseDelayMs = 0L,
            jitterRangeMs = 0L..10L
        )
        val config = RetryConfig(
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.FIXED,
            globalBaseDelayMs = 1000L,
            globalJitterRangeMs = 0L..100L,
            perSiteOverrides = mapOf("bad-site" to badStrategy)
        )

        val exception = shouldThrow<IllegalArgumentException> {
            RetryPolicyResolver(config)
        }
        exception.message shouldBe "Invalid retry config for site 'bad-site': baseDelayMs must be > 0, got 0"
    }
})
