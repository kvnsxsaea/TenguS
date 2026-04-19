package com.tengus.scraper

import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.proxy.ProxyPool
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import org.slf4j.LoggerFactory
import kotlin.reflect.full.primaryConstructor

/**
 * Factory that creates [BaseScraper] instances by delegating site identifier
 * lookups to [ScraperRegistry] and injecting all shared dependencies via
 * Kotlin reflection.
 *
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 24.6
 */
class ScraperFactory(
    private val registry: ScraperRegistry,
    private val stealthManager: StealthManager,
    private val proxyPool: ProxyPool,
    private val userAgentRotator: UserAgentRotator,
    private val sessionManager: SessionManager,
    private val humanBehaviorEngine: HumanBehaviorEngine,
    private val sessionWarmupEngine: SessionWarmupEngine
) {
    private val logger = LoggerFactory.getLogger(ScraperFactory::class.java)

    /**
     * Creates a [BaseScraper] instance for the given [siteId].
     *
     * 1. Delegates to [ScraperRegistry.lookup] to resolve the scraper class.
     *    If the siteId is unregistered, the registry throws [IllegalArgumentException].
     * 2. Finds the primary constructor via Kotlin reflection.
     * 3. Instantiates the scraper with all shared dependencies injected.
     *
     * @param siteId the site identifier declared by the target scraper
     * @return a fully constructed [BaseScraper] instance
     * @throws IllegalArgumentException if no scraper is registered for [siteId]
     * @throws IllegalStateException if the scraper class has no primary constructor
     */
    fun createScraper(siteId: String): BaseScraper {
        val scraperClass = registry.lookup(siteId)

        val constructor = scraperClass.primaryConstructor
            ?: throw IllegalStateException(
                "Scraper class '${scraperClass.qualifiedName}' for site '$siteId' " +
                    "has no primary constructor. All BaseScraper subclasses must declare a primary constructor."
            )

        val instance = constructor.call(
            stealthManager,
            proxyPool,
            userAgentRotator,
            sessionManager,
            humanBehaviorEngine,
            sessionWarmupEngine
        )

        logger.info("Created scraper for siteId='{}': {}", siteId, scraperClass.qualifiedName)
        return instance
    }
}
