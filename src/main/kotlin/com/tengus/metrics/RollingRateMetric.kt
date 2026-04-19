package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted at a configurable interval with rolling success/failure rates per site.
 *
 * Validates: Requirement 19.3
 */
data class RollingRateMetric(
    val metricType: String = "rolling_rate",
    val siteId: String,
    val successRate: Double,
    val failureRate: Double,
    val timestamp: Instant
)
