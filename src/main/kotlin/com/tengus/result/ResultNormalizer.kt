package com.tengus.result

import com.tengus.model.NormalizedScrapeResult
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import com.tengus.model.ValidationResult
import org.slf4j.LoggerFactory

/**
 * Transforms raw scrape results into a standardized schema and validates
 * them against the common result contract.
 *
 * Validates: Requirements 25.1, 25.2, 25.3, 25.4, 25.5, 25.6
 */
class ResultNormalizer(private val scraperVersion: String) {

    private val logger = LoggerFactory.getLogger(ResultNormalizer::class.java)

    /**
     * Transforms a [ScrapeResult] into a [NormalizedScrapeResult] by mapping
     * fields and attaching the scraper version.
     */
    fun normalize(result: ScrapeResult, job: ScrapeJob): NormalizedScrapeResult {
        return NormalizedScrapeResult(
            jobId = result.jobId,
            siteId = result.siteId,
            sourceUrl = result.sourceUrl,
            extractionTimestamp = result.extractedAt,
            scraperVersion = scraperVersion,
            data = result.data
        )
    }

    /**
     * Validates that a [NormalizedScrapeResult] conforms to the common schema:
     * all required fields must be present and non-blank.
     *
     * Returns a [ValidationResult] with `valid=true` when all checks pass,
     * or `valid=false` with a list of error descriptions.
     * Logs validation errors at WARN level with the job ID.
     */
    fun validate(normalized: NormalizedScrapeResult): ValidationResult {
        val errors = mutableListOf<String>()

        if (normalized.jobId.isBlank()) {
            errors.add("jobId must not be blank")
        }
        if (normalized.siteId.isBlank()) {
            errors.add("siteId must not be blank")
        }
        if (normalized.sourceUrl.isBlank()) {
            errors.add("sourceUrl must not be blank")
        }
        if (normalized.scraperVersion.isBlank()) {
            errors.add("scraperVersion must not be blank")
        }
        if (normalized.data.isEmpty()) {
            errors.add("data must not be empty")
        }

        if (errors.isNotEmpty()) {
            logger.warn("Validation failed for jobId={}: {}", normalized.jobId, errors)
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors
        )
    }
}
