package com.tengus.ratelimit

import com.tengus.config.DomainRateLimit
import com.tengus.config.RateLimitConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedList

/**
 * Per-domain sliding window rate limiter.
 *
 * Tracks request timestamps per domain and enforces a configurable
 * maximum number of requests per time window. Supports per-domain
 * configuration overrides with global default fallback.
 */
class RateLimiter(private val config: RateLimitConfig) {

    private val logger = LoggerFactory.getLogger(RateLimiter::class.java)

    // Per-domain request timestamp windows. Each domain maps to a list of
    // epoch-millisecond timestamps representing recent requests.
    private val windows: ConcurrentHashMap<String, LinkedList<Long>> = ConcurrentHashMap()

    /**
     * Returns the rate limit configuration for the given domain.
     * Uses domain-specific overrides when available, otherwise falls back
     * to global defaults.
     */
    fun configForDomain(domain: String): DomainRateLimit {
        return config.domainOverrides[domain]
            ?: DomainRateLimit(config.defaultMaxRequests, config.defaultWindowMs)
    }

    /**
     * Blocks until a rate limit permit is available for the given domain.
     * If the domain's sliding window is full, sleeps until the oldest
     * timestamp expires out of the window, then records the new request.
     */
    fun acquire(domain: String) {
        val domainConfig = configForDomain(domain)
        val timestamps = windows.computeIfAbsent(domain) { LinkedList() }

        synchronized(timestamps) {
            while (true) {
                val now = System.currentTimeMillis()
                pruneExpired(timestamps, now, domainConfig.windowMs)

                if (timestamps.size < domainConfig.maxRequests) {
                    timestamps.add(now)
                    return
                }

                // Calculate how long to wait until the oldest request expires
                val oldest = timestamps.first
                val waitMs = (oldest + domainConfig.windowMs) - now
                if (waitMs > 0) {
                    logger.debug(
                        "Rate limit exceeded for domain '{}', waiting {}ms",
                        domain, waitMs
                    )
                    Thread.sleep(waitMs)
                }
            }
        }
    }

    /**
     * Non-blocking attempt to acquire a rate limit permit for the given domain.
     * Returns true if the permit was granted (and the request is recorded),
     * false if the rate limit is currently exceeded.
     */
    fun tryAcquire(domain: String): Boolean {
        val domainConfig = configForDomain(domain)
        val timestamps = windows.computeIfAbsent(domain) { LinkedList() }

        synchronized(timestamps) {
            val now = System.currentTimeMillis()
            pruneExpired(timestamps, now, domainConfig.windowMs)

            if (timestamps.size < domainConfig.maxRequests) {
                timestamps.add(now)
                return true
            }
            return false
        }
    }

    /**
     * Removes timestamps that have fallen outside the sliding window.
     */
    private fun pruneExpired(timestamps: LinkedList<Long>, now: Long, windowMs: Long) {
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first < cutoff) {
            timestamps.removeFirst()
        }
    }
}
