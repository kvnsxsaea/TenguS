package com.tengus.scraper

import com.microsoft.playwright.Page
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import com.tengus.proxy.ProxyPool
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Abstract base class for all site scrapers. Implements the Template Method
 * pattern: [execute] defines the full scraping lifecycle while subclasses
 * provide site-specific extraction logic via [extract].
 *
 * Lifecycle: create browser context → select user-agent & proxy → apply stealth
 * (fingerprint, bot patches, headers) → session warmup → invoke [extract] →
 * collect result → close browser context.
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 18.2
 */
abstract class BaseScraper(
    private val stealthManager: StealthManager,
    private val proxyPool: ProxyPool,
    private val userAgentRotator: UserAgentRotator,
    private val sessionManager: SessionManager,
    private val humanBehaviorEngine: HumanBehaviorEngine,
    private val sessionWarmupEngine: SessionWarmupEngine
) {
    private val logger = LoggerFactory.getLogger(BaseScraper::class.java)

    /** Site identifier declared by each concrete scraper. */
    abstract val siteId: String

    /**
     * Site-specific extraction logic. Receives an open Playwright [page]
     * and the [job] definition; returns the raw [ScrapeResult].
     */
    abstract fun extract(page: Page, job: ScrapeJob): ScrapeResult

    /**
     * Template method that orchestrates the full scrape lifecycle for a [job].
     *
     * 1. Log start (INFO) with jobId, siteId, targetUrl
     * 2. Extract domain from targetUrl
     * 3. Select user-agent and proxy for the domain
     * 4. Create isolated browser session via SessionManager
     * 5. Generate & apply fingerprint, bot-detection patches, normalized headers
     * 6. Create a new page from the context
     * 7. Run session warmup
     * 8. Invoke [extract]
     * 9. Close page and destroy session
     * 10. Log completion with elapsed time (INFO)
     * 11. Return ScrapeResult
     *
     * On ANY exception: log error, close page, destroy session, re-throw.
     */
    fun execute(job: ScrapeJob): ScrapeResult {
        logger.info("Scrape started: jobId={}, siteId={}, targetUrl={}", job.jobId, siteId, job.targetUrl)
        val startTime = System.currentTimeMillis()

        val domain = URI(job.targetUrl).host

        // Select user-agent and proxy for the target domain
        val userAgent = userAgentRotator.select(domain)
        val proxy = proxyPool.selectProxy(domain)

        // Create isolated browser context for this job
        val context = sessionManager.createSession(job.jobId)
        var page: Page? = null

        try {
            // Apply stealth: fingerprint, bot patches, header normalization
            val fingerprint = stealthManager.generateFingerprintProfile(userAgent)
            stealthManager.applyFingerprint(context, fingerprint)
            stealthManager.applyBotDetectionPatches(context)
            stealthManager.normalizeHeaders(context, userAgent)

            // Create a new page from the context
            page = context.newPage()

            // Session warmup: visit benign pages to build browsing profile
            sessionWarmupEngine.warmup(context, page)

            // Invoke site-specific extraction
            val result = extract(page, job)

            // Close page and destroy session
            page.close()
            page = null
            sessionManager.destroySession(job.jobId)

            val elapsedMs = System.currentTimeMillis() - startTime
            logger.info("Scrape completed: jobId={}, siteId={}, elapsedMs={}", job.jobId, siteId, elapsedMs)

            return result
        } catch (ex: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            logger.error("Scrape failed: jobId={}, siteId={}, elapsedMs={}, error={}",
                job.jobId, siteId, elapsedMs, ex.message, ex)

            // Cleanup: close page and destroy session
            try { page?.close() } catch (_: Exception) {}
            try { sessionManager.destroySession(job.jobId) } catch (_: Exception) {}

            throw ex
        }
    }
}
