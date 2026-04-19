package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted at a configurable interval with proxy health counts per domain.
 *
 * Validates: Requirement 19.6
 */
data class ProxyHealthMetric(
    val metricType: String = "proxy_health",
    val domain: String,
    val healthyCount: Int,
    val blockedCount: Int,
    val timestamp: Instant
)
