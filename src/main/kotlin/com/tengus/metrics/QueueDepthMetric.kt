package com.tengus.metrics

import java.time.Instant

/**
 * Metric emitted at a configurable interval with current queue depths.
 *
 * Validates: Requirement 19.4
 */
data class QueueDepthMetric(
    val metricType: String = "queue_depth",
    val jobsQueueDepth: Int,
    val dlqDepth: Int,
    val timestamp: Instant
)
