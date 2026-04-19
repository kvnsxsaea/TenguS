package com.tengus

import com.microsoft.playwright.Playwright
import com.rabbitmq.client.ConnectionFactory
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.config.ConfigLoader
import com.tengus.config.ProxyHealthConfig
import com.tengus.controller.ScraperController
import com.tengus.metrics.MetricsCollector
import com.tengus.proxy.ProxyHealthMonitor
import com.tengus.proxy.ProxyPool
import com.tengus.ratelimit.RateLimiter
import com.tengus.resilience.CircuitBreakerManager
import com.tengus.resilience.RetryPolicyResolver
import com.tengus.result.ResultNormalizer
import com.tengus.scraper.ScraperFactory
import com.tengus.scraper.ScraperRegistry
import com.tengus.session.SessionManager
import com.tengus.session.SessionWarmupEngine
import com.tengus.stealth.StealthManager
import com.tengus.stealth.UserAgentRotator
import com.tengus.webhook.WebhookDispatcher
import org.slf4j.LoggerFactory

/**
 * Application entry point for the TenguS web scraper service.
 *
 * Loads configuration, wires all components with proper dependency injection
 * order, starts scraper discovery and the controller, and launches a daemon
 * health-logging thread.
 *
 * Validates: Requirements 16.1, 17.3, 24.1
 */
fun main() {
    val logger = LoggerFactory.getLogger("com.tengus.Application")

    logger.info("TenguS starting up...")

    // 1. Load configuration
    val config = ConfigLoader().loadFromClasspath()
    logger.info("Configuration loaded successfully")

    // 2. Create RabbitMQ ConnectionFactory
    val connectionFactory = ConnectionFactory().apply {
        host = config.rabbitmq.host
        port = config.rabbitmq.port
        username = config.rabbitmq.username
        password = config.rabbitmq.password
    }

    // 3. Create ProxyHealthMonitor with sensible defaults
    val proxyHealthConfig = ProxyHealthConfig(
        slidingWindowMs = 60_000L,
        failureRateThreshold = 0.5,
        cooldownMs = 30_000L
    )
    val proxyHealthMonitor = ProxyHealthMonitor(proxyHealthConfig)

    // 4. Create ProxyPool
    val proxyPool = ProxyPool(config.proxy, proxyHealthMonitor)

    // 5. Create UserAgentRotator
    val userAgentRotator = UserAgentRotator(config.userAgent)

    // 6. Create StealthManager
    val stealthManager = StealthManager(userAgentRotator, config.stealth)

    // 7. Create RateLimiter
    val rateLimiter = RateLimiter(config.rateLimiter)

    // 8. Create HumanBehaviorEngine
    val humanBehaviorEngine = HumanBehaviorEngine(config.humanBehavior)

    // 9. Create Playwright Browser instance
    val playwright = Playwright.create()
    val browser = playwright.chromium().launch()

    // 10. Create SessionManager
    val sessionManager = SessionManager(browser)

    // 11. Create SessionWarmupEngine
    val sessionWarmupEngine = SessionWarmupEngine(humanBehaviorEngine, config.warmup)

    // 12. Create CircuitBreakerManager
    val circuitBreakerManager = CircuitBreakerManager(config.circuitBreaker)

    // 13. Create RetryPolicyResolver
    val retryPolicyResolver = RetryPolicyResolver(config.retry)

    // 14. Create ResultNormalizer
    val resultNormalizer = ResultNormalizer(scraperVersion = "1.0.0-SNAPSHOT")

    // 15. Create WebhookDispatcher
    val webhookDispatcher = WebhookDispatcher(config.webhook)

    // 16. Create MetricsCollector
    val metricsCollector = MetricsCollector(config.metrics)

    // 17. Create ScraperRegistry and discover scrapers
    val scraperRegistry = ScraperRegistry(config.scraperRegistry.scanPackage)
    scraperRegistry.discover()

    // 18. Create ScraperFactory
    val scraperFactory = ScraperFactory(
        registry = scraperRegistry,
        stealthManager = stealthManager,
        proxyPool = proxyPool,
        userAgentRotator = userAgentRotator,
        sessionManager = sessionManager,
        humanBehaviorEngine = humanBehaviorEngine,
        sessionWarmupEngine = sessionWarmupEngine
    )

    // 19. Create ScraperController
    val controller = ScraperController(
        connectionFactory = connectionFactory,
        scraperFactory = scraperFactory,
        circuitBreaker = circuitBreakerManager,
        rateLimiter = rateLimiter,
        retryPolicyResolver = retryPolicyResolver,
        resultNormalizer = resultNormalizer,
        webhookDispatcher = webhookDispatcher,
        metricsCollector = metricsCollector,
        config = config
    )

    // Start health logging daemon thread
    val healthThread = Thread({
        val healthLogger = LoggerFactory.getLogger("com.tengus.HealthMonitor")
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(config.metrics.reportingIntervalMs)
                val registeredCount = scraperRegistry.registeredSiteIds().size
                val rabbitConnected = try {
                    connectionFactory.newConnection().use { true }
                } catch (_: Exception) {
                    false
                }
                healthLogger.info(
                    "Health status: rabbitmqConnected={}, registeredScrapers={}",
                    rabbitConnected,
                    registeredCount
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                healthLogger.warn("Health check failed: {}", e.message)
            }
        }
    }, "health-monitor")
    healthThread.isDaemon = true
    healthThread.start()

    // 20. Start the controller
    logger.info("Starting ScraperController...")
    controller.start()

    logger.info("TenguS is running")
}
