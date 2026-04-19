package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted at a configurable interval with average scrape duration per site.
 *
 * Validates: Requirement 19.5
 */
data class AverageDurationMetric(
    val metricType: String = "avg_duration",
    val siteId: String,
    val averageDurationMs: Double,
    val timestamp: Instant
)
