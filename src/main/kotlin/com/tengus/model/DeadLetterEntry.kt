package com.tengus.model

import java.time.Instant

/**
 * An entry in the dead-letter queue, preserving the original job and failure context.
 *
 * Validates: Requirements 2.1
 */
data class DeadLetterEntry(
    val jobId: String,
    val siteId: String,
    val originalPayload: ScrapeJob,
    val failureReason: String,
    val retryCount: Int,
    val enqueuedAt: Instant
)
