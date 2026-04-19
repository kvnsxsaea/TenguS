package com.tengus.scraper

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.config.*
import com.tengus.model.*
import com.tengus.proxy.ProxyHealthMonitor
import com.tengus.proxy.ProxyPool
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.time.Instant

class BaseScraperTest : FunSpec({

    // --- Helpers to build mocks ---

    fun buildMocks(): TestMocks {
        val context = mockk<BrowserContext>(relaxed = true)
        val page = mockk<Page>(relaxed = true)
        every { context.newPage() } returns page

        val sessionManager = mockk<SessionManager>(relaxed = true)
        every { sessionManager.createSession(any()) } returns context

        val stealthManager = mockk<StealthManager>(relaxed = true)
        every { stealthManager.generateFingerprintProfile(any()) } returns FingerprintProfile(
            viewport = ViewportSize(1920, 1080),
            timezone = "America/New_York",
            language = "en-US",
            platform = "Win32",
            userAgent = "Mozilla/5.0 Chrome/120",
            webglVendor = "Google Inc.",
            webglRenderer = "ANGLE"
        )

        val userAgentRotator = mockk<UserAgentRotator>(relaxed = true)
        every { userAgentRotator.select(any()) } returns "Mozilla/5.0 Chrome/120"

        val proxyPool = mockk<ProxyPool>(relaxed = true)
        every { proxyPool.selectProxy(any()) } returns ProxyEndpoint("proxy.example.com", 8080)

        val humanBehaviorEngine = mockk<HumanBehaviorEngine>(relaxed = true)
        val sessionWarmupEngine = mockk<SessionWarmupEngine>(relaxed = true)

        return TestMocks(
            context, page, sessionManager, stealthManager,
            userAgentRotator, proxyPool, humanBehaviorEngine, sessionWarmupEngine
        )
    }

    fun createScraper(mocks: TestMocks, extractFn: (Page, ScrapeJob) -> ScrapeResult): BaseScraper {
        return object : BaseScraper(
            mocks.stealthManager, mocks.proxyPool, mocks.userAgentRotator,
            mocks.sessionManager, mocks.humanBehaviorEngine, mocks.sessionWarmupEngine
        ) {
            override val siteId = "test-site"
            override fun extract(page: Page, job: ScrapeJob): ScrapeResult = extractFn(page, job)
        }
    }

    val sampleJob = ScrapeJob(
        jobId = "job-001",
        targetUrl = "https://example.com/page",
        siteId = "test-site"
    )

    test("execute returns ScrapeResult from extract on success") {
        val mocks = buildMocks()
        val expectedResult = ScrapeResult(
            jobId = "job-001", siteId = "test-site",
            sourceUrl = "https://example.com/page",
            extractedAt = Instant.now(), data = mapOf("title" to "Hello")
        )
        val scraper = createScraper(mocks) { _, _ -> expectedResult }

        val result = scraper.execute(sampleJob)

        result shouldBe expectedResult
    }

    test("execute calls lifecycle steps in correct order") {
        val mocks = buildMocks()
        val callOrder = mutableListOf<String>()

        every { mocks.userAgentRotator.select(any()) } answers {
            callOrder.add("selectUserAgent"); "Mozilla/5.0 Chrome/120"
        }
        every { mocks.proxyPool.selectProxy(any()) } answers {
            callOrder.add("selectProxy"); ProxyEndpoint("proxy.example.com", 8080)
        }
        every { mocks.sessionManager.createSession(any()) } answers {
            callOrder.add("createSession"); mocks.context
        }
        every { mocks.stealthManager.generateFingerprintProfile(any()) } answers {
            callOrder.add("generateFingerprint")
            FingerprintProfile(ViewportSize(1920, 1080), "America/New_York", "en-US", "Win32",
                "Mozilla/5.0 Chrome/120", "Google Inc.", "ANGLE")
        }
        every { mocks.stealthManager.applyFingerprint(any(), any()) } answers { callOrder.add("applyFingerprint") }
        every { mocks.stealthManager.applyBotDetectionPatches(any()) } answers { callOrder.add("applyBotPatches") }
        every { mocks.stealthManager.normalizeHeaders(any(), any()) } answers { callOrder.add("normalizeHeaders") }
        every { mocks.context.newPage() } answers { callOrder.add("newPage"); mocks.page }
        every { mocks.sessionWarmupEngine.warmup(any(), any()) } answers { callOrder.add("warmup") }

        val scraper = createScraper(mocks) { _, _ ->
            callOrder.add("extract")
            ScrapeResult("job-001", "test-site", "https://example.com/page", Instant.now(), emptyMap())
        }

        scraper.execute(sampleJob)

        callOrder shouldBe listOf(
            "selectUserAgent", "selectProxy", "createSession",
            "generateFingerprint", "applyFingerprint", "applyBotPatches", "normalizeHeaders",
            "newPage", "warmup", "extract"
        )
    }

    test("execute closes page and destroys session on success") {
        val mocks = buildMocks()
        val scraper = createScraper(mocks) { _, _ ->
            ScrapeResult("job-001", "test-site", "https://example.com/page", Instant.now(), emptyMap())
        }

        scraper.execute(sampleJob)

        verify(exactly = 1) { mocks.page.close() }
        verify(exactly = 1) { mocks.sessionManager.destroySession("job-001") }
    }

    test("execute closes page and destroys session on extraction failure") {
        val mocks = buildMocks()
        val scraper = createScraper(mocks) { _, _ ->
            throw RuntimeException("Extraction failed")
        }

        shouldThrow<RuntimeException> { scraper.execute(sampleJob) }

        verify(exactly = 1) { mocks.page.close() }
        verify(exactly = 1) { mocks.sessionManager.destroySession("job-001") }
    }

    test("execute propagates exception to caller after cleanup") {
        val mocks = buildMocks()
        val scraper = createScraper(mocks) { _, _ ->
            throw IllegalStateException("Target blocked")
        }

        val ex = shouldThrow<IllegalStateException> { scraper.execute(sampleJob) }
        ex.message shouldBe "Target blocked"
    }

    test("execute extracts domain from targetUrl correctly") {
        val mocks = buildMocks()
        val capturedDomain = slot<String>()
        every { mocks.userAgentRotator.select(capture(capturedDomain)) } returns "Mozilla/5.0 Chrome/120"

        val scraper = createScraper(mocks) { _, _ ->
            ScrapeResult("job-001", "test-site", "https://shop.example.org/items", Instant.now(), emptyMap())
        }

        val job = sampleJob.copy(targetUrl = "https://shop.example.org/items?q=test")
        scraper.execute(job)

        capturedDomain.captured shouldBe "shop.example.org"
    }
})

data class TestMocks(
    val context: BrowserContext,
    val page: Page,
    val sessionManager: SessionManager,
    val stealthManager: StealthManager,
    val userAgentRotator: UserAgentRotator,
    val proxyPool: ProxyPool,
    val humanBehaviorEngine: HumanBehaviorEngine,
    val sessionWarmupEngine: SessionWarmupEngine
)
