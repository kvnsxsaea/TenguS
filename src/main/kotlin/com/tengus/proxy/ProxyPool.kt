package com.tengus.proxy

import com.tengus.config.ProxyConfig
import com.tengus.model.ProxyEndpoint
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages proxy endpoints with rotation, health tracking, and recovery.
 * Integrates with [ProxyHealthMonitor] for domain-level blocking awareness.
 *
 * Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5
 */
class ProxyPool(
    private val config: ProxyConfig,
    private val healthMonitor: ProxyHealthMonitor
) {
    private val logger = LoggerFactory.getLogger(ProxyPool::class.java)

    /** Globally unhealthy proxies (connection failures). */
    private val unhealthyProxies = ConcurrentHashMap.newKeySet<ProxyEndpoint>()

    /** Last-used proxy index per domain for rotation. */
    private val lastUsedByDomain = ConcurrentHashMap<String, ProxyEndpoint>()

    /**
     * Select a healthy proxy for the given [domain], rotating so consecutive
     * calls for the same domain use different proxies when more than one
     * healthy proxy is available.
     *
     * @throws IllegalStateException when no healthy proxies are available
     */
    fun selectProxy(domain: String): ProxyEndpoint {
        val candidates = healthyProxiesForDomain(domain)
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "No healthy proxies available for domain '$domain'. " +
                    "All ${config.endpoints.size} configured proxies are either globally unhealthy or blocked for this domain."
            )
        }

        if (candidates.size == 1) {
            lastUsedByDomain[domain] = candidates.first()
            return candidates.first()
        }

        val lastUsed = lastUsedByDomain[domain]
        val rotated = candidates.filter { it != lastUsed }
        val selected = rotated.firstOrNull() ?: candidates.first()

        lastUsedByDomain[domain] = selected
        return selected
    }

    /**
     * Mark a proxy as globally unhealthy (e.g. connection failure).
     */
    fun markUnhealthy(proxy: ProxyEndpoint) {
        unhealthyProxies.add(proxy)
        logger.warn("Proxy marked unhealthy: {}:{}", proxy.host, proxy.port)
    }

    /**
     * Restore a previously unhealthy proxy back to the rotation pool.
     */
    fun restoreProxy(proxy: ProxyEndpoint) {
        unhealthyProxies.remove(proxy)
        logger.warn("Proxy restored to healthy: {}:{}", proxy.host, proxy.port)
    }

    /**
     * Return proxies that are both globally healthy AND not blocked for the
     * specific [domain] according to the [ProxyHealthMonitor].
     */
    fun healthyProxiesForDomain(domain: String): List<ProxyEndpoint> {
        return config.endpoints.filter { proxy ->
            proxy !in unhealthyProxies && !healthMonitor.isBlocked(proxy, domain)
        }
    }
}
