package com.tengus.model

import java.time.Instant

/**
 * Standardized scrape result conforming to the common result schema.
 *
 * Validates: Requirements 14.2, 2.3
 */
data class NormalizedScrapeResult(
    val jobId: String,
    val siteId: String,
    val sourceUrl: String,
    val extractionTimestamp: Instant,
    val scraperVersion: String,
    val data: Map<String, Any>
)
