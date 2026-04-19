package com.tengus.model

import java.time.Instant

/**
 * Raw extraction output produced by a Site Scraper.
 *
 * Validates: Requirements 14.2
 */
data class ScrapeResult(
    val jobId: String,
    val siteId: String,
    val sourceUrl: String,
    val extractedAt: Instant,
    val data: Map<String, Any>
)
