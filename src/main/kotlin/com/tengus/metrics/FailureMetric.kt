package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted when a scrape job fails after all retries are exhausted.
 *
 * Validates: Requirement 19.2
 */
data class FailureMetric(
    val metricType: String = "scrape_failure",
    val jobId: String,
    val siteId: String,
    val failureReason: String,
    val retryCount: Int,
    val status: String = "failure",
    val timestamp: Instant
)
