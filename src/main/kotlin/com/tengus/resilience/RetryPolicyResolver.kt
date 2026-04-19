package com.tengus.resilience

import com.tengus.config.BackoffType
import com.tengus.config.RetryConfig
import com.tengus.config.RetryStrategy
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Resolves per-site or global retry strategies and computes backoff delays.
 *
 * At construction time, validates all per-site override configurations.
 * Accepts an injectable [Random] for deterministic testing.
 */
class RetryPolicyResolver(
    private val config: RetryConfig,
    private val random: Random = Random.Default
) {

    private val logger = LoggerFactory.getLogger(RetryPolicyResolver::class.java)

    init {
        validateConfig()
    }

    /**
     * Returns the retry strategy for the given [siteId].
     * Uses per-site overrides when available, otherwise builds a strategy from global config values.
     */
    fun resolve(siteId: String): RetryStrategy {
        return config.perSiteOverrides[siteId]
            ?: RetryStrategy(
                maxRetries = config.globalMaxRetries,
                backoffType = config.globalBackoffType,
                baseDelayMs = config.globalBaseDelayMs,
                jitterRangeMs = config.globalJitterRangeMs
            )
    }

    /**
     * Computes the delay in milliseconds for the given [attempt] number using the
     * backoff type defined in [strategy], plus a random jitter.
     *
     * - FIXED: `baseDelayMs + jitter`
     * - LINEAR: `baseDelayMs * attempt + jitter`
     * - EXPONENTIAL: `baseDelayMs * 2^(attempt-1) + jitter`
     *
     * [attempt] is 1-based (first retry = attempt 1).
     */
    fun computeDelay(strategy: RetryStrategy, attempt: Int): Long {
        val baseDelay = when (strategy.backoffType) {
            BackoffType.FIXED -> strategy.baseDelayMs
            BackoffType.LINEAR -> strategy.baseDelayMs * attempt
            BackoffType.EXPONENTIAL -> strategy.baseDelayMs * (1L shl (attempt - 1))
        }
        val jitter = if (strategy.jitterRangeMs.isEmpty()) {
            0L
        } else {
            random.nextLong(strategy.jitterRangeMs.first, strategy.jitterRangeMs.last + 1)
        }
        return baseDelay + jitter
    }

    /**
     * Validates all per-site retry overrides at construction time.
     * Throws [IllegalArgumentException] if any override has invalid values.
     */
    private fun validateConfig() {
        config.perSiteOverrides.forEach { (siteId, strategy) ->
            require(strategy.maxRetries >= 0) {
                "Invalid retry config for site '$siteId': maxRetries must be >= 0, got ${strategy.maxRetries}"
            }
            require(strategy.baseDelayMs > 0) {
                "Invalid retry config for site '$siteId': baseDelayMs must be > 0, got ${strategy.baseDelayMs}"
            }
            require(strategy.jitterRangeMs.first >= 0) {
                "Invalid retry config for site '$siteId': jitterRangeMs start must be >= 0, got ${strategy.jitterRangeMs.first}"
            }
            require(strategy.jitterRangeMs.last >= strategy.jitterRangeMs.first) {
                "Invalid retry config for site '$siteId': jitterRangeMs end (${strategy.jitterRangeMs.last}) must be >= start (${strategy.jitterRangeMs.first})"
            }
        }
        logger.info("Validated {} per-site retry overrides", config.perSiteOverrides.size)
    }
}
