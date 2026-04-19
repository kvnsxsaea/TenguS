package com.tengus.model

import java.time.Instant

/**
 * A scrape job consumed from the message queue.
 *
 * Validates: Requirements 2.1, 2.2, 2.3
 */
data class ScrapeJob(
    val jobId: String,
    val targetUrl: String,
    val siteId: String,
    val callbackUrl: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
    val retryCount: Int = 0,
    val createdAt: Instant = Instant.now()
)
