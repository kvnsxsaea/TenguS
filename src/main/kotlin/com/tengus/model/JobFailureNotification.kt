package com.tengus.model

import java.time.Instant

/**
 * Failure notification sent to webhook callback URLs when a job fails after all retries.
 *
 * Validates: Requirements 2.1
 */
data class JobFailureNotification(
    val jobId: String,
    val siteId: String,
    val failureReason: String,
    val timestamp: Instant
)
