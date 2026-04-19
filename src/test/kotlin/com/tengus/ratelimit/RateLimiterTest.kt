package com.tengus.ratelimit

import com.tengus.config.DomainRateLimit
import com.tengus.config.RateLimitConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class RateLimiterTest : FunSpec({

    // --- Requirement 12.1, 12.4: Sliding window limits requests ---

    test("tryAcquire returns true up to maxRequests then false") {
        val config = RateLimitConfig(
            defaultMaxRequests = 3,
            defaultWindowMs = 5000L
        )
        val limiter = RateLimiter(config)

        limiter.tryAcquire("example.com").shouldBeTrue()
        limiter.tryAcquire("example.com").shouldBeTrue()
        limiter.tryAcquire("example.com").shouldBeTrue()
        limiter.tryAcquire("example.com").shouldBeFalse()
    }

    // --- Requirement 12.4: After window expires, permits become available ---

    test("permits become available again after the window expires") {
        val config = RateLimitConfig(
            defaultMaxRequests = 2,
            defaultWindowMs = 100L
        )
        val limiter = RateLimiter(config)

        limiter.tryAcquire("example.com").shouldBeTrue()
        limiter.tryAcquire("example.com").shouldBeTrue()
        limiter.tryAcquire("example.com").shouldBeFalse()

        Thread.sleep(150)

        limiter.tryAcquire("example.com").shouldBeTrue()
    }

    // --- Requirement 12.5: configForDomain returns domain-specific override ---

    test("configForDomain returns domain-specific override when configured") {
        val override = DomainRateLimit(maxRequests = 10, windowMs = 2000L)
        val config = RateLimitConfig(
            defaultMaxRequests = 5,
            defaultWindowMs = 1000L,
            domainOverrides = mapOf("special.com" to override)
        )
        val limiter = RateLimiter(config)

        val result = limiter.configForDomain("special.com")
        result.maxRequests shouldBe 10
        result.windowMs shouldBe 2000L
    }

    // --- Requirement 12.5: configForDomain returns global defaults ---

    test("configForDomain returns global defaults when no override exists") {
        val config = RateLimitConfig(
            defaultMaxRequests = 5,
            defaultWindowMs = 1000L,
            domainOverrides = mapOf("other.com" to DomainRateLimit(10, 2000L))
        )
        val limiter = RateLimiter(config)

        val result = limiter.configForDomain("unknown.com")
        result.maxRequests shouldBe 5
        result.windowMs shouldBe 1000L
    }

    // --- Requirement 12.2: acquire blocks when rate exceeded ---

    test("acquire blocks until window expires when rate exceeded") {
        val windowMs = 200L
        val config = RateLimitConfig(
            defaultMaxRequests = 1,
            defaultWindowMs = windowMs
        )
        val limiter = RateLimiter(config)

        limiter.acquire("example.com") // fills the window

        val start = System.currentTimeMillis()
        limiter.acquire("example.com") // should block until window expires
        val elapsed = System.currentTimeMillis() - start

        elapsed.shouldBeGreaterThanOrEqual(windowMs - 50) // allow small timing tolerance
    }

    // --- Requirement 12.1: Different domains have independent rate limits ---

    test("different domains have independent rate limits") {
        val config = RateLimitConfig(
            defaultMaxRequests = 1,
            defaultWindowMs = 5000L
        )
        val limiter = RateLimiter(config)

        limiter.tryAcquire("domain-a.com").shouldBeTrue()
        limiter.tryAcquire("domain-a.com").shouldBeFalse()

        // domain-b should still have permits available
        limiter.tryAcquire("domain-b.com").shouldBeTrue()
    }

    // --- Requirement 12.5: Domain override limits are enforced independently ---

    test("domain override limits are enforced correctly") {
        val config = RateLimitConfig(
            defaultMaxRequests = 2,
            defaultWindowMs = 5000L,
            domainOverrides = mapOf(
                "strict.com" to DomainRateLimit(maxRequests = 1, windowMs = 5000L)
            )
        )
        val limiter = RateLimiter(config)

        // strict.com allows only 1
        limiter.tryAcquire("strict.com").shouldBeTrue()
        limiter.tryAcquire("strict.com").shouldBeFalse()

        // default domain allows 2
        limiter.tryAcquire("normal.com").shouldBeTrue()
        limiter.tryAcquire("normal.com").shouldBeTrue()
        limiter.tryAcquire("normal.com").shouldBeFalse()
    }
})
