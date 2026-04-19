package com.tengus.scraper

import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.proxy.ProxyPool
import com.tengus.scraper.sites.ExampleSiteScraper
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

/**
 * Unit tests for [ScraperFactory].
 *
 * Validates: Requirements 4.2, 4.3
 */
class ScraperFactoryTest : FunSpec({

    // Shared mocks for all tests
    val stealthManager = mockk<StealthManager>(relaxed = true)
    val proxyPool = mockk<ProxyPool>(relaxed = true)
    val userAgentRotator = mockk<UserAgentRotator>(relaxed = true)
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val humanBehaviorEngine = mockk<HumanBehaviorEngine>(relaxed = true)
    val sessionWarmupEngine = mockk<SessionWarmupEngine>(relaxed = true)

    fun buildFactory(): ScraperFactory {
        val registry = ScraperRegistry("com.tengus.scraper.sites")
        registry.register("example", ExampleSiteScraper::class)

        return ScraperFactory(
            registry = registry,
            stealthManager = stealthManager,
            proxyPool = proxyPool,
            userAgentRotator = userAgentRotator,
            sessionManager = sessionManager,
            humanBehaviorEngine = humanBehaviorEngine,
            sessionWarmupEngine = sessionWarmupEngine
        )
    }

    test("createScraper returns a BaseScraper instance for a registered site ID") {
        val factory = buildFactory()

        val scraper = factory.createScraper("example")

        scraper.shouldBeInstanceOf<BaseScraper>()
        scraper.shouldBeInstanceOf<ExampleSiteScraper>()
    }

    test("createScraper throws for unregistered site ID") {
        val factory = buildFactory()

        shouldThrow<IllegalArgumentException> {
            factory.createScraper("nonexistent-site")
        }
    }

    test("created scraper has correct siteId") {
        val factory = buildFactory()

        val scraper = factory.createScraper("example")

        scraper.siteId shouldBe "example"
    }
})
