package com.tengus.scraper.sites

import com.microsoft.playwright.Page
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import com.tengus.proxy.ProxyPool
import com.tengus.scraper.BaseScraper
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import java.time.Instant

/**
 * Reference [BaseScraper] implementation that extracts the page title from
 * the job's target URL. Serves as a template for building real site scrapers.
 *
 * Validates: Requirements 4.5, 5.1
 */
class ExampleSiteScraper(
    stealthManager: StealthManager,
    proxyPool: ProxyPool,
    userAgentRotator: UserAgentRotator,
    sessionManager: SessionManager,
    humanBehaviorEngine: HumanBehaviorEngine,
    sessionWarmupEngine: SessionWarmupEngine
) : BaseScraper(
    stealthManager,
    proxyPool,
    userAgentRotator,
    sessionManager,
    humanBehaviorEngine,
    sessionWarmupEngine
) {
    companion object {
        const val SITE_ID = "example"
    }

    override val siteId: String = SITE_ID

    /**
     * Navigates to the job's target URL and extracts the page title.
     */
    override fun extract(page: Page, job: ScrapeJob): ScrapeResult {
        page.navigate(job.targetUrl)

        val title = page.title() ?: ""

        return ScrapeResult(
            jobId = job.jobId,
            siteId = siteId,
            sourceUrl = job.targetUrl,
            extractedAt = Instant.now(),
            data = mapOf("title" to title)
        )
    }
}
