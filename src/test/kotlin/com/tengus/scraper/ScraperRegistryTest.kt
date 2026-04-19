package com.tengus.scraper

import com.microsoft.playwright.Page
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import com.tengus.proxy.ProxyPool
import com.tengus.scraper.sites.ExampleSiteScraper
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Unit tests for [ScraperRegistry].
 *
 * Validates: Requirements 24.1, 24.4
 */
class ScraperRegistryTest : FunSpec({

    // --- Helper: a dummy scraper class for manual registration tests ---

    fun createRegistry() = ScraperRegistry("com.tengus.scraper.sites")

    test("register and lookup return the registered scraper class") {
        val registry = createRegistry()
        registry.register("test-site", TestScraperA::class)

        registry.lookup("test-site") shouldBe TestScraperA::class
    }

    test("registeredSiteIds returns all registered identifiers") {
        val registry = createRegistry()
        registry.register("site-a", TestScraperA::class)
        registry.register("site-b", TestScraperB::class)

        registry.registeredSiteIds() shouldContainExactlyInAnyOrder listOf("site-a", "site-b")
    }

    test("duplicate site ID throws IllegalStateException with both class names") {
        val registry = createRegistry()
        registry.register("duplicate", TestScraperA::class)

        val ex = shouldThrow<IllegalStateException> {
            registry.register("duplicate", TestScraperB::class)
        }
        ex.message shouldContain "TestScraperA"
        ex.message shouldContain "TestScraperB"
        ex.message shouldContain "duplicate"
    }

    test("registering the same class for the same siteId is idempotent") {
        val registry = createRegistry()
        registry.register("same", TestScraperA::class)
        // Should not throw
        registry.register("same", TestScraperA::class)

        registry.lookup("same") shouldBe TestScraperA::class
    }

    test("lookup throws IllegalArgumentException for unregistered site ID") {
        val registry = createRegistry()
        registry.register("known", TestScraperA::class)

        val ex = shouldThrow<IllegalArgumentException> {
            registry.lookup("unknown-site")
        }
        ex.message shouldContain "unknown-site"
    }

    test("discover finds ExampleSiteScraper in com.tengus.scraper.sites package") {
        val registry = ScraperRegistry("com.tengus.scraper.sites")
        registry.discover()

        // The discover method resolves siteId via companion SITE_ID or class name fallback.
        // ExampleSiteScraper's SITE_ID is "example", but the resolveSiteId fallback
        // derives "example-site" from the class name. Verify whichever the registry uses.
        val siteIds = registry.registeredSiteIds()
        siteIds.size shouldBe 1

        // The registry discovered ExampleSiteScraper — verify it's registered
        val siteId = siteIds.first()
        registry.lookup(siteId) shouldBe ExampleSiteScraper::class
    }
})

// --- Test helper scraper classes (minimal, for registration tests only) ---

class TestScraperA(
    stealthManager: StealthManager,
    proxyPool: ProxyPool,
    userAgentRotator: UserAgentRotator,
    sessionManager: SessionManager,
    humanBehaviorEngine: HumanBehaviorEngine,
    sessionWarmupEngine: SessionWarmupEngine
) : BaseScraper(stealthManager, proxyPool, userAgentRotator, sessionManager, humanBehaviorEngine, sessionWarmupEngine) {
    companion object { const val SITE_ID = "test-a" }
    override val siteId: String = SITE_ID
    override fun extract(page: Page, job: ScrapeJob): ScrapeResult =
        ScrapeResult(job.jobId, siteId, job.targetUrl, Instant.now(), emptyMap())
}

class TestScraperB(
    stealthManager: StealthManager,
    proxyPool: ProxyPool,
    userAgentRotator: UserAgentRotator,
    sessionManager: SessionManager,
    humanBehaviorEngine: HumanBehaviorEngine,
    sessionWarmupEngine: SessionWarmupEngine
) : BaseScraper(stealthManager, proxyPool, userAgentRotator, sessionManager, humanBehaviorEngine, sessionWarmupEngine) {
    companion object { const val SITE_ID = "test-b" }
    override val siteId: String = SITE_ID
    override fun extract(page: Page, job: ScrapeJob): ScrapeResult =
        ScrapeResult(job.jobId, siteId, job.targetUrl, Instant.now(), emptyMap())
}
