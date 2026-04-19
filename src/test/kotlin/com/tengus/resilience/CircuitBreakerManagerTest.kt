package com.tengus.resilience

import com.tengus.config.CircuitBreakerConfig
import com.tengus.config.DomainCircuitBreakerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A mutable clock for deterministic testing. Delegates to an internal
 * fixed clock that can be advanced programmatically.
 */
private class MutableClock(private var currentMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
    override fun millis(): Long = currentMillis
    fun advance(ms: Long) { currentMillis += ms }
}

class CircuitBreakerManagerTest : FunSpec({

    // --- Requirement 21.1, 21.2: CLOSED → OPEN when failures exceed threshold ---

    test("CLOSED to OPEN when failures exceed threshold") {
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 3,
            defaultCooldownMs = 5000L
        )
        val manager = CircuitBreakerManager(config)

        manager.checkState("example.com") shouldBe CircuitState.CLOSED

        manager.recordFailure("example.com")
        manager.checkState("example.com") shouldBe CircuitState.CLOSED

        manager.recordFailure("example.com")
        manager.checkState("example.com") shouldBe CircuitState.CLOSED

        // Third failure trips the breaker
        manager.recordFailure("example.com")
        manager.getState("example.com") shouldBe CircuitState.OPEN
    }

    // --- Requirement 21.4: OPEN → HALF_OPEN after cooldown elapses ---

    test("OPEN to HALF_OPEN after cooldown elapses") {
        val clock = MutableClock(1_000_000L)
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 2,
            defaultCooldownMs = 1000L
        )
        val manager = CircuitBreakerManager(config, clock)

        // Trip the breaker
        manager.recordFailure("example.com")
        manager.recordFailure("example.com")
        manager.getState("example.com") shouldBe CircuitState.OPEN

        // Before cooldown — still OPEN
        clock.advance(999)
        manager.checkState("example.com") shouldBe CircuitState.OPEN

        // At cooldown boundary — transitions to HALF_OPEN
        clock.advance(1)
        manager.checkState("example.com") shouldBe CircuitState.HALF_OPEN
    }

    // --- Requirement 21.6: HALF_OPEN → CLOSED on success ---

    test("HALF_OPEN to CLOSED on success") {
        val clock = MutableClock(1_000_000L)
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 2,
            defaultCooldownMs = 500L
        )
        val manager = CircuitBreakerManager(config, clock)

        // Trip to OPEN, then advance to HALF_OPEN
        manager.recordFailure("example.com")
        manager.recordFailure("example.com")
        clock.advance(500)
        manager.checkState("example.com") shouldBe CircuitState.HALF_OPEN

        // Success transitions to CLOSED
        manager.recordSuccess("example.com")
        manager.getState("example.com") shouldBe CircuitState.CLOSED
    }

    // --- Requirement 21.7: HALF_OPEN → OPEN on failure ---

    test("HALF_OPEN to OPEN on failure") {
        val clock = MutableClock(1_000_000L)
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 2,
            defaultCooldownMs = 500L
        )
        val manager = CircuitBreakerManager(config, clock)

        // Trip to OPEN, then advance to HALF_OPEN
        manager.recordFailure("example.com")
        manager.recordFailure("example.com")
        clock.advance(500)
        manager.checkState("example.com") shouldBe CircuitState.HALF_OPEN

        // Failure transitions back to OPEN
        manager.recordFailure("example.com")
        manager.getState("example.com") shouldBe CircuitState.OPEN
    }

    // --- Requirement 21.1: Success in CLOSED resets failure count ---

    test("success in CLOSED resets failure count") {
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 3,
            defaultCooldownMs = 5000L
        )
        val manager = CircuitBreakerManager(config)

        // Accumulate 2 failures (below threshold of 3)
        manager.recordFailure("example.com")
        manager.recordFailure("example.com")
        manager.checkState("example.com") shouldBe CircuitState.CLOSED

        // Success resets the count
        manager.recordSuccess("example.com")

        // Now 2 more failures should NOT trip the breaker (count was reset)
        manager.recordFailure("example.com")
        manager.recordFailure("example.com")
        manager.checkState("example.com") shouldBe CircuitState.CLOSED

        // Third failure after reset trips it
        manager.recordFailure("example.com")
        manager.getState("example.com") shouldBe CircuitState.OPEN
    }

    // --- Requirement 21.6: Per-domain config overrides vs global defaults ---

    test("per-domain config overrides vs global defaults") {
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 5,
            defaultCooldownMs = 10000L,
            domainOverrides = mapOf(
                "strict.com" to DomainCircuitBreakerConfig(
                    failureThreshold = 2,
                    cooldownMs = 500L
                )
            )
        )
        val manager = CircuitBreakerManager(config)

        // strict.com uses override (threshold=2)
        val strictConfig = manager.configForDomain("strict.com")
        strictConfig.failureThreshold shouldBe 2
        strictConfig.cooldownMs shouldBe 500L

        // other.com uses global defaults
        val defaultConfig = manager.configForDomain("other.com")
        defaultConfig.failureThreshold shouldBe 5
        defaultConfig.cooldownMs shouldBe 10000L

        // Verify strict.com trips after 2 failures
        manager.recordFailure("strict.com")
        manager.recordFailure("strict.com")
        manager.getState("strict.com") shouldBe CircuitState.OPEN

        // other.com should still be CLOSED after 2 failures
        manager.recordFailure("other.com")
        manager.recordFailure("other.com")
        manager.getState("other.com") shouldBe CircuitState.CLOSED
    }

    // --- Requirement 21.1: getState returns CLOSED for unknown domains ---

    test("getState returns CLOSED for unknown domains") {
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 3,
            defaultCooldownMs = 5000L
        )
        val manager = CircuitBreakerManager(config)

        manager.getState("never-seen.com") shouldBe CircuitState.CLOSED
    }

    // --- Requirement 21.2: Different domains have independent circuit breakers ---

    test("different domains have independent circuit breakers") {
        val config = CircuitBreakerConfig(
            defaultFailureThreshold = 2,
            defaultCooldownMs = 5000L
        )
        val manager = CircuitBreakerManager(config)

        // Trip domain-a
        manager.recordFailure("domain-a.com")
        manager.recordFailure("domain-a.com")
        manager.getState("domain-a.com") shouldBe CircuitState.OPEN

        // domain-b should be unaffected
        manager.getState("domain-b.com") shouldBe CircuitState.CLOSED
        manager.checkState("domain-b.com") shouldBe CircuitState.CLOSED

        // domain-b can still record failures independently
        manager.recordFailure("domain-b.com")
        manager.getState("domain-b.com") shouldBe CircuitState.CLOSED
    }
})
