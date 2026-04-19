package com.tengus.integration

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.AMQP
import com.tengus.config.*
import com.tengus.metrics.MetricsCollector
import com.tengus.model.*
import com.tengus.ratelimit.RateLimiter
import com.tengus.resilience.CircuitBreakerManager
import com.tengus.resilience.CircuitState
import com.tengus.resilience.RetryPolicyResolver
import com.tengus.result.ResultNormalizer
import com.tengus.scraper.BaseScraper
import com.tengus.scraper.ScraperFactory
import com.tengus.serialization.JsonMapper
import com.tengus.webhook.WebhookDispatcher
import com.tengus.controller.ScraperController
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.*
import java.time.Instant

/**
 * End-to-end integration tests verifying the full job lifecycle flows
 * through ScraperController: consume → dispatch → extract → normalize → publish → webhook.
 *
 * Validates: Requirements 3.2, 3.4, 14.1, 14.5, 25.6, 26.2
 */
class EndToEndJobFlowTest : FunSpec({

    val appConfig = AppConfig(
        rabbitmq = RabbitMqConfig(
            host = "localhost", port = 5672,
            username = "guest", password = "guest",
            jobsQueue = "scrape_jobs",
            resultsQueue = "scrape_results",
            dlqQueue = "scrape_dlq"
        ),
        rateLimiter = RateLimitConfig(defaultMaxRequests = 10, defaultWindowMs = 60000L),
        proxy = ProxyConfig(endpoints = emptyList(), connectTimeoutMs = 5000L, healthCheckIntervalMs = 30000L),
        userAgent = UserAgentConfig(agents = emptyList()),
        retry = RetryConfig(
            globalMaxRetries = 2,
            globalBackoffType = BackoffType.EXPONENTIAL,
            globalBaseDelayMs = 10L,
            globalJitterRangeMs = 0L..0L
        ),
        humanBehavior = HumanBehaviorConfig(),
        stealth = StealthConfig(
            viewports = emptyList(), timezones = emptyList(),
            languages = emptyList(), platforms = emptyList(),
            webglVendors = emptyList(), webglRenderers = emptyList()
        ),
        circuitBreaker = CircuitBreakerConfig(defaultFailureThreshold = 5, defaultCooldownMs = 30000L),
        warmup = WarmupConfig(urls = emptyList(), minPages = 0, maxPages = 0, pageTimeoutMs = 5000L),
        webhook = WebhookConfig(hmacSecret = "test-secret", maxRetries = 1, baseDelayMs = 10L),
        metrics = MetricsConfig(reportingIntervalMs = 10000L, slidingWindowMs = 60000L),
        shutdown = ShutdownConfig(gracePeriodSeconds = 5),
        scraperRegistry = ScraperRegistryConfig(scanPackage = "com.tengus.scraper.sites")
    )

    fun buildMocks(): Map<String, Any> {
        val connectionFactory = mockk<ConnectionFactory>()
        val connection = mockk<Connection>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val scraperFactory = mockk<ScraperFactory>()
        val circuitBreaker = mockk<CircuitBreakerManager>()
        val rateLimiter = mockk<RateLimiter>(relaxed = true)
        val retryPolicyResolver = RetryPolicyResolver(appConfig.retry)
        val resultNormalizer = ResultNormalizer("1.0.0")
        val webhookDispatcher = mockk<WebhookDispatcher>(relaxed = true)
        val metricsCollector = mockk<MetricsCollector>(relaxed = true)

        every { connectionFactory.newConnection() } returns connection
        every { connection.createChannel() } returns channel

        return mapOf(
            "connectionFactory" to connectionFactory,
            "connection" to connection,
            "channel" to channel,
            "scraperFactory" to scraperFactory,
            "circuitBreaker" to circuitBreaker,
            "rateLimiter" to rateLimiter,
            "retryPolicyResolver" to retryPolicyResolver,
            "resultNormalizer" to resultNormalizer,
            "webhookDispatcher" to webhookDispatcher,
            "metricsCollector" to metricsCollector
        )
    }

    fun buildController(mocks: Map<String, Any>): ScraperController {
        val controller = ScraperController(
            connectionFactory = mocks["connectionFactory"] as ConnectionFactory,
            scraperFactory = mocks["scraperFactory"] as ScraperFactory,
            circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager,
            rateLimiter = mocks["rateLimiter"] as RateLimiter,
            retryPolicyResolver = mocks["retryPolicyResolver"] as RetryPolicyResolver,
            resultNormalizer = mocks["resultNormalizer"] as ResultNormalizer,
            webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher,
            metricsCollector = mocks["metricsCollector"] as MetricsCollector,
            config = appConfig
        )
        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, mocks["channel"] as Channel)
        return controller
    }

    fun makeDelivery(body: ByteArray, deliveryTag: Long = 1L): Delivery {
        val envelope = Envelope(deliveryTag, false, "", "scrape_jobs")
        val properties = AMQP.BasicProperties.Builder().build()
        return Delivery(envelope, properties, body)
    }

    fun sampleJob(
        jobId: String = "e2e-job-1",
        targetUrl: String = "https://example.com/products",
        siteId: String = "example",
        callbackUrl: String? = null,
        retryCount: Int = 0
    ) = ScrapeJob(
        jobId = jobId, targetUrl = targetUrl, siteId = siteId,
        callbackUrl = callbackUrl, retryCount = retryCount,
        createdAt = Instant.parse("2024-06-01T12:00:00Z")
    )


    // --- Full lifecycle: consume → dispatch → normalize → publish → webhook ---

    test("full lifecycle: handleMessage consumes job, dispatches scraper, normalizes, publishes result, and dispatches webhook") {
        val mocks = buildMocks()
        val controller = buildController(mocks)
        val channel = mocks["channel"] as Channel
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher
        val metricsCollector = mocks["metricsCollector"] as MetricsCollector

        val job = sampleJob(callbackUrl = "https://hooks.example.com/result")
        val scraper = mockk<BaseScraper>()
        val rawResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("name" to "Widget", "price" to 19.99)
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns rawResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        val body = JsonMapper.mapper.writeValueAsBytes(job)
        val delivery = makeDelivery(body)

        // Execute the full flow
        controller.handleMessage(delivery)

        // 1. Message acknowledged (consumed successfully)
        verify { channel.basicAck(1L, false) }

        // 2. Scraper dispatched via factory
        verify { scraperFactory.createScraper("example") }
        verify { scraper.execute(any()) }

        // 3. Result normalized and published to results queue
        val resultSlot = slot<ByteArray>()
        verify { channel.basicPublish("", "scrape_results", null, capture(resultSlot)) }
        val publishedResult = JsonMapper.mapper.readValue(resultSlot.captured, NormalizedScrapeResult::class.java)
        publishedResult.jobId shouldBe "e2e-job-1"
        publishedResult.siteId shouldBe "example"
        publishedResult.sourceUrl shouldBe "https://example.com/products"
        publishedResult.scraperVersion shouldBe "1.0.0"
        publishedResult.data["name"] shouldBe "Widget"

        // 4. Webhook dispatched with normalized result
        verify { webhookDispatcher.dispatch("https://hooks.example.com/result", any()) }

        // 5. Success metric emitted
        verify { metricsCollector.emitSuccess("e2e-job-1", "example", any()) }

        // 6. Circuit breaker recorded success
        verify { circuitBreaker.recordSuccess("example.com") }
    }

    // --- Retry → DLQ → failure webhook end-to-end ---

    test("retry exhaustion flow: retryable failures exhaust retries then route to DLQ with failure webhook") {
        val mocks = buildMocks()
        val controller = buildController(mocks)
        val channel = mocks["channel"] as Channel
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher
        val metricsCollector = mocks["metricsCollector"] as MetricsCollector

        val scraper = mockk<BaseScraper>()
        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } throws RuntimeException("Connection timeout")
        every { circuitBreaker.recordFailure("example.com") } just Runs

        // Simulate retry 0 → re-enqueue with count 1
        val job0 = sampleJob(callbackUrl = "https://hooks.example.com/fail", retryCount = 0)
        controller.handleMessage(makeDelivery(JsonMapper.mapper.writeValueAsBytes(job0), 10L))

        // Verify re-enqueue with retryCount=1
        val reEnqueueSlot1 = slot<ByteArray>()
        verify { channel.basicPublish("", "scrape_jobs", null, capture(reEnqueueSlot1)) }
        val retried1 = JsonMapper.mapper.readValue(reEnqueueSlot1.captured, ScrapeJob::class.java)
        retried1.retryCount shouldBe 1

        clearMocks(channel, answers = false, recordedCalls = true)

        // Simulate retry 1 → re-enqueue with count 2
        val job1 = sampleJob(callbackUrl = "https://hooks.example.com/fail", retryCount = 1)
        controller.handleMessage(makeDelivery(JsonMapper.mapper.writeValueAsBytes(job1), 11L))

        val reEnqueueSlot2 = slot<ByteArray>()
        verify { channel.basicPublish("", "scrape_jobs", null, capture(reEnqueueSlot2)) }
        val retried2 = JsonMapper.mapper.readValue(reEnqueueSlot2.captured, ScrapeJob::class.java)
        retried2.retryCount shouldBe 2

        clearMocks(channel, answers = false, recordedCalls = true)

        // Simulate retry 2 → max retries (2) exceeded → DLQ
        val job2 = sampleJob(callbackUrl = "https://hooks.example.com/fail", retryCount = 2)
        controller.handleMessage(makeDelivery(JsonMapper.mapper.writeValueAsBytes(job2), 12L))

        // Should route to DLQ (not re-enqueue to jobs queue)
        verify { channel.basicPublish("", "scrape_dlq", null, any<ByteArray>()) }

        // Failure webhook dispatched
        verify { webhookDispatcher.dispatchFailure("https://hooks.example.com/fail", any()) }

        // Failure metric emitted
        verify { metricsCollector.emitFailure("e2e-job-1", "example", any(), any()) }

        // DLQ store has the entry
        val dlqEntries = controller.listDeadLetterMessages()
        dlqEntries shouldHaveSize 1
        dlqEntries[0].jobId shouldBe "e2e-job-1"
    }

    // --- Success flow without webhook (no callbackUrl) ---

    test("success flow without webhook: job completes, result published, no webhook dispatched") {
        val mocks = buildMocks()
        val controller = buildController(mocks)
        val channel = mocks["channel"] as Channel
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher

        val job = sampleJob(callbackUrl = null) // no callback
        val scraper = mockk<BaseScraper>()
        val rawResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("title" to "Product Page")
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns rawResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        controller.handleMessage(makeDelivery(JsonMapper.mapper.writeValueAsBytes(job)))

        // Result published
        verify { channel.basicPublish("", "scrape_results", null, any<ByteArray>()) }

        // No webhook dispatched
        verify(exactly = 0) { webhookDispatcher.dispatch(any(), any()) }
        verify(exactly = 0) { webhookDispatcher.dispatchFailure(any(), any()) }
    }
})
