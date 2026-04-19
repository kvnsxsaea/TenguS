package com.tengus.resilience

import com.tengus.config.CircuitBreakerConfig
import com.tengus.config.DomainCircuitBreakerConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit breaker states for per-domain failure tracking.
 */
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/**
 * Per-domain circuit breaker with closed/open/half-open states.
 *
 * Tracks consecutive failure counts per domain and transitions between states:
 * - CLOSED → OPEN when consecutive failures exceed the configured threshold
 * - OPEN → HALF_OPEN after the configured cooldown period elapses
 * - HALF_OPEN → CLOSED on a successful request (resets failure count)
 * - HALF_OPEN → OPEN on a failed request (restarts cooldown)
 *
 * Thread-safe via ConcurrentHashMap and synchronized blocks on per-domain state.
 * Accepts an injectable [Clock] for testability.
 */
class CircuitBreakerManager(
    private val config: CircuitBreakerConfig,
    private val clock: Clock = Clock.systemUTC()
) {

    private val logger = LoggerFactory.getLogger(CircuitBreakerManager::class.java)

    /**
     * Internal mutable state tracked per domain.
     */
    private data class DomainState(
        var state: CircuitState = CircuitState.CLOSED,
        var consecutiveFailures: Int = 0,
        var openedAt: Long = 0L
    )

    private val states: ConcurrentHashMap<String, DomainState> = ConcurrentHashMap()

    /**
     * Returns the effective circuit breaker configuration for the given domain.
     * Uses domain-specific overrides when available, otherwise falls back to global defaults.
     */
    fun configForDomain(domain: String): DomainCircuitBreakerConfig {
        return config.domainOverrides[domain]
            ?: DomainCircuitBreakerConfig(config.defaultFailureThreshold, config.defaultCooldownMs)
    }

    /**
     * Returns the current circuit state for the given domain, triggering
     * an OPEN → HALF_OPEN transition if the cooldown period has elapsed.
     *
     * This is the primary entry point the controller should call before
     * dispatching a job.
     */
    fun checkState(domain: String): CircuitState {
        val domainState = states.computeIfAbsent(domain) { DomainState() }
        val domainConfig = configForDomain(domain)

        synchronized(domainState) {
            if (domainState.state == CircuitState.OPEN) {
                val elapsed = clock.millis() - domainState.openedAt
                if (elapsed >= domainConfig.cooldownMs) {
                    val previousFailures = domainState.consecutiveFailures
                    domainState.state = CircuitState.HALF_OPEN
                    logger.warn(
                        "Circuit breaker OPEN → HALF_OPEN for domain '{}' (failures={})",
                        domain, previousFailures
                    )
                }
            }
            return domainState.state
        }
    }

    /**
     * Records a successful request for the given domain.
     *
     * - If HALF_OPEN: transitions to CLOSED and resets failure count.
     * - If CLOSED: resets failure count.
     */
    fun recordSuccess(domain: String) {
        val domainState = states.computeIfAbsent(domain) { DomainState() }

        synchronized(domainState) {
            when (domainState.state) {
                CircuitState.HALF_OPEN -> {
                    domainState.state = CircuitState.CLOSED
                    domainState.consecutiveFailures = 0
                    logger.warn(
                        "Circuit breaker HALF_OPEN → CLOSED for domain '{}' (failures=0)",
                        domain
                    )
                }
                CircuitState.CLOSED -> {
                    domainState.consecutiveFailures = 0
                }
                CircuitState.OPEN -> {
                    // Should not normally happen; ignore.
                }
            }
        }
    }

    /**
     * Records a failed request for the given domain.
     *
     * - If CLOSED: increments failure count; transitions to OPEN if threshold exceeded.
     * - If HALF_OPEN: transitions back to OPEN and restarts cooldown.
     */
    fun recordFailure(domain: String) {
        val domainState = states.computeIfAbsent(domain) { DomainState() }
        val domainConfig = configForDomain(domain)

        synchronized(domainState) {
            domainState.consecutiveFailures++

            when (domainState.state) {
                CircuitState.CLOSED -> {
                    if (domainState.consecutiveFailures >= domainConfig.failureThreshold) {
                        domainState.state = CircuitState.OPEN
                        domainState.openedAt = clock.millis()
                        logger.warn(
                            "Circuit breaker CLOSED → OPEN for domain '{}' (failures={})",
                            domain, domainState.consecutiveFailures
                        )
                    }
                }
                CircuitState.HALF_OPEN -> {
                    domainState.state = CircuitState.OPEN
                    domainState.openedAt = clock.millis()
                    logger.warn(
                        "Circuit breaker HALF_OPEN → OPEN for domain '{}' (failures={})",
                        domain, domainState.consecutiveFailures
                    )
                }
                CircuitState.OPEN -> {
                    // Already open; just increment count.
                }
            }
        }
    }

    /**
     * Returns the raw circuit state for the given domain without triggering
     * any state transitions. Returns CLOSED for unknown domains.
     */
    fun getState(domain: String): CircuitState {
        val domainState = states[domain] ?: return CircuitState.CLOSED
        synchronized(domainState) {
            return domainState.state
        }
    }
}
