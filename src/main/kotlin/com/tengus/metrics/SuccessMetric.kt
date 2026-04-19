package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted when a scrape job completes successfully.
 *
 * Validates: Requirement 19.1
 */
data class SuccessMetric(
    val metricType: String = "scrape_success",
    val jobId: String,
    val siteId: String,
    val durationMs: Long,
    val status: String = "success",
    val timestamp: Instant
)
