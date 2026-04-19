package com.tengus.proxy

import com.tengus.config.ProxyHealthConfig
import com.tengus.model.BlockingSignal
import com.tengus.model.ProxyEndpoint
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-proxy-per-domain failure rates over a sliding window and
 * proactively removes blocked proxies from rotation.
 *
 * Validates: Requirements 27.1, 27.2, 27.3, 27.4, 27.5, 27.6
 */
class ProxyHealthMonitor(private val config: ProxyHealthConfig, private val clock: () -> Long = System::currentTimeMillis) {

    private val logger = LoggerFactory.getLogger(ProxyHealthMonitor::class.java)

    /** Composite key for per-proxy-per-domain tracking. */
    private data class ProxyDomainKey(val proxy: ProxyEndpoint, val domain: String)

    /** A timestamped event (success or failure). */
    private data class Event(val timestampMs: Long, val success: Boolean)

    /** Events per (proxy, domain) pair. */
    private val events = ConcurrentHashMap<ProxyDomainKey, MutableList<Event>>()

    /** Recheck schedule: maps (proxy, domain) → earliest recheck time in epoch ms. */
    private val recheckSchedule = ConcurrentHashMap<ProxyDomainKey, Long>()

    /**
     * Record a successful request through [proxy] to [domain].
     */
    fun recordSuccess(proxy: ProxyEndpoint, domain: String) {
        val key = ProxyDomainKey(proxy, domain)
        val wasBlocked = isBlocked(proxy, domain)
        addEvent(key, Event(clock(), success = true))
        if (wasBlocked && !isBlocked(proxy, domain)) {
            recheckSchedule.remove(key)
            logger.warn("Proxy restored: proxy={}:{} domain={} failureRate={}", proxy.host, proxy.port, domain, failureRate(key))
        }
    }

    /**
     * Record a failure for [proxy] on [domain] with the given blocking [signal].
     */
    fun recordFailure(proxy: ProxyEndpoint, domain: String, signal: BlockingSignal) {
        val key = ProxyDomainKey(proxy, domain)
        val wasPreviouslyBlocked = isBlocked(proxy, domain)
        addEvent(key, Event(clock(), success = false))
        if (!wasPreviouslyBlocked && isBlocked(proxy, domain)) {
            logger.warn("Proxy blocked: proxy={}:{} domain={} signal={} failureRate={}", proxy.host, proxy.port, domain, signal, failureRate(key))
        }
    }

    /**
     * Returns true when the failure rate for [proxy] on [domain] exceeds the
     * configured threshold within the sliding window, or when a recheck is
     * scheduled and the cooldown has not yet elapsed.
     */
    fun isBlocked(proxy: ProxyEndpoint, domain: String): Boolean {
        val key = ProxyDomainKey(proxy, domain)

        // If a recheck is scheduled and cooldown hasn't elapsed, still blocked
        val recheckAt = recheckSchedule[key]
        if (recheckAt != null && clock() < recheckAt) {
            return true
        }

        return failureRate(key) > config.failureRateThreshold
    }

    /**
     * Schedule a recheck for [proxy] on [domain] after the configured cooldown.
     */
    fun scheduleRecheck(proxy: ProxyEndpoint, domain: String) {
        val key = ProxyDomainKey(proxy, domain)
        val recheckAt = clock() + config.cooldownMs
        recheckSchedule[key] = recheckAt
    }

    /**
     * Classify an HTTP response as a blocking signal.
     *
     * - HTTP 403 → [BlockingSignal.HTTP_403]
     * - Response body containing "captcha" (case-insensitive) → [BlockingSignal.CAPTCHA_CHALLENGE]
     * - Response body containing "connection reset" (case-insensitive) → [BlockingSignal.CONNECTION_RESET]
     * - Otherwise → null
     */
    fun classifyBlockingSignal(statusCode: Int, responseBody: String?): BlockingSignal? {
        if (statusCode == 403) return BlockingSignal.HTTP_403
        if (responseBody != null) {
            if (responseBody.contains("captcha", ignoreCase = true)) return BlockingSignal.CAPTCHA_CHALLENGE
            if (responseBody.contains("connection reset", ignoreCase = true)) return BlockingSignal.CONNECTION_RESET
        }
        return null
    }

    // --- Internal helpers ---

    private fun addEvent(key: ProxyDomainKey, event: Event) {
        events.getOrPut(key) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(event)
                pruneOldEvents(list)
            }
        }
    }

    private fun pruneOldEvents(list: MutableList<Event>) {
        val cutoff = clock() - config.slidingWindowMs
        list.removeAll { it.timestampMs < cutoff }
    }

    private fun failureRate(key: ProxyDomainKey): Double {
        val list = events[key] ?: return 0.0
        synchronized(list) {
            pruneOldEvents(list)
            if (list.isEmpty()) return 0.0
            val failures = list.count { !it.success }
            return failures.toDouble() / list.size
        }
    }
}
