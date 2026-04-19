package com.tengus.controller

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import java.time.Instant

class ScraperControllerTest : FunSpec({

    // Shared config
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
            globalMaxRetries = 3,
            globalBackoffType = BackoffType.EXPONENTIAL,
            globalBaseDelayMs = 100L,
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
        webhook = WebhookConfig(hmacSecret = "secret", maxRetries = 2, baseDelayMs = 100L),
        metrics = MetricsConfig(reportingIntervalMs = 10000L, slidingWindowMs = 60000L),
        shutdown = ShutdownConfig(gracePeriodSeconds = 5),
        scraperRegistry = ScraperRegistryConfig(scanPackage = "com.tengus.scraper.sites")
    )

    fun createMocks(): Map<String, Any> {
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

    fun createController(mocks: Map<String, Any>): ScraperController {
        return ScraperController(
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
    }

    fun makeDelivery(body: ByteArray, deliveryTag: Long = 1L): Delivery {
        val envelope = Envelope(deliveryTag, false, "", "scrape_jobs")
        val properties = AMQP.BasicProperties.Builder().build()
        return Delivery(envelope, properties, body)
    }

    fun sampleJob(
        jobId: String = "job-1",
        targetUrl: String = "https://example.com/page",
        siteId: String = "example",
        callbackUrl: String? = null,
        retryCount: Int = 0
    ): ScrapeJob = ScrapeJob(
        jobId = jobId,
        targetUrl = targetUrl,
        siteId = siteId,
        callbackUrl = callbackUrl,
        retryCount = retryCount,
        createdAt = Instant.parse("2024-01-01T00:00:00Z")
    )


    // --- handleMessage tests ---

    test("handleMessage deserializes valid ScrapeJob and dispatches successfully") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager

        val job = sampleJob()
        val scraper = mockk<BaseScraper>()
        val scrapeResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("title" to "Test")
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns scrapeResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        // Need to set up channel on the controller via start-like initialization
        // We'll use reflection to set the channel directly for unit testing
        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val body = JsonMapper.mapper.writeValueAsBytes(job)
        val delivery = makeDelivery(body)

        controller.handleMessage(delivery)

        verify { channel.basicAck(1L, false) }
        verify { scraperFactory.createScraper("example") }
    }

    test("handleMessage rejects and logs on invalid JSON") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val delivery = makeDelivery("not valid json".toByteArray())

        controller.handleMessage(delivery)

        verify { channel.basicReject(1L, false) }
        verify(exactly = 0) { channel.basicAck(any(), any()) }
    }

    test("handleMessage rejects malformed JSON missing required fields") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        // JSON missing required fields (jobId, targetUrl, siteId)
        val delivery = makeDelivery("""{"extra":"field"}""".toByteArray())

        controller.handleMessage(delivery)

        // Jackson with Kotlin module will fail on missing non-nullable fields
        verify { channel.basicReject(1L, false) }
    }


    // --- dispatchJob tests ---

    test("dispatchJob re-enqueues when circuit breaker is OPEN") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        every { circuitBreaker.checkState("example.com") } returns CircuitState.OPEN

        controller.dispatchJob(job)

        // Should re-enqueue to jobs queue
        verify { channel.basicPublish("", "scrape_jobs", null, any<ByteArray>()) }
        verify(exactly = 0) { (mocks["scraperFactory"] as ScraperFactory).createScraper(any()) }
    }

    test("dispatchJob allows single job through in HALF_OPEN state") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        val scraper = mockk<BaseScraper>()
        val scrapeResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("title" to "Test")
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.HALF_OPEN
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns scrapeResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        controller.dispatchJob(job)

        verify { scraperFactory.createScraper("example") }
        verify { circuitBreaker.recordSuccess("example.com") }
    }

    test("dispatchJob logs warning when extracted data is empty") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        val scraper = mockk<BaseScraper>()
        val scrapeResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = emptyMap() // empty data
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns scrapeResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        // Should not throw — just logs warning
        controller.dispatchJob(job)

        verify { circuitBreaker.recordSuccess("example.com") }
    }


    // --- Retry and DLQ tests ---

    test("handleMessage retries on retryable error with incremented count") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob(retryCount = 0)
        val scraper = mockk<BaseScraper>()

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } throws RuntimeException("Connection timeout")
        every { circuitBreaker.recordFailure("example.com") } just Runs

        val body = JsonMapper.mapper.writeValueAsBytes(job)
        val delivery = makeDelivery(body)

        controller.handleMessage(delivery)

        // Should ack original and re-enqueue with incremented retry count
        verify { channel.basicAck(1L, false) }
        val publishSlot = slot<ByteArray>()
        verify { channel.basicPublish("", "scrape_jobs", null, capture(publishSlot)) }
        val reEnqueued = JsonMapper.mapper.readValue(publishSlot.captured, ScrapeJob::class.java)
        reEnqueued.retryCount shouldBe 1
    }

    test("handleMessage routes to DLQ when max retries exceeded") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        // retryCount = 3, maxRetries = 3 → next would be 4 > 3 → DLQ
        val job = sampleJob(retryCount = 3)
        val scraper = mockk<BaseScraper>()

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } throws RuntimeException("Connection timeout")
        every { circuitBreaker.recordFailure("example.com") } just Runs

        val body = JsonMapper.mapper.writeValueAsBytes(job)
        val delivery = makeDelivery(body)

        controller.handleMessage(delivery)

        // Should publish to DLQ
        verify { channel.basicPublish("", "scrape_dlq", null, any<ByteArray>()) }
    }

    test("handleMessage routes unrecoverable error directly to DLQ") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        val scraper = mockk<BaseScraper>()

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } throws IllegalStateException("Unrecoverable parsing error")
        every { circuitBreaker.recordFailure("example.com") } just Runs

        val body = JsonMapper.mapper.writeValueAsBytes(job)
        val delivery = makeDelivery(body)

        controller.handleMessage(delivery)

        // Should nack (not requeue) and route to DLQ
        verify { channel.basicNack(1L, false, false) }
        verify { channel.basicPublish("", "scrape_dlq", null, any<ByteArray>()) }
    }


    // --- DLQ management tests ---

    test("routeToDeadLetterQueue stores entry and publishes to DLQ") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        controller.routeToDeadLetterQueue(job, "test failure", 2)

        val entries = controller.listDeadLetterMessages()
        entries shouldHaveSize 1
        entries[0].jobId shouldBe "job-1"
        entries[0].failureReason shouldBe "test failure"
        entries[0].retryCount shouldBe 2

        verify { channel.basicPublish("", "scrape_dlq", null, any<ByteArray>()) }
    }

    test("replayDeadLetterMessage re-enqueues with retry count reset to zero") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob(retryCount = 3)
        controller.routeToDeadLetterQueue(job, "test failure", 3)

        controller.replayDeadLetterMessage("job-1")

        val publishSlot = slot<ByteArray>()
        // First call is DLQ publish, second is replay to jobs queue
        verify(atLeast = 1) { channel.basicPublish("", "scrape_jobs", null, capture(publishSlot)) }
        val replayed = JsonMapper.mapper.readValue(publishSlot.captured, ScrapeJob::class.java)
        replayed.retryCount shouldBe 0

        // Entry should be removed from store
        controller.listDeadLetterMessages() shouldHaveSize 0
    }

    test("purgeDeadLetterMessage removes entry from store") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        controller.routeToDeadLetterQueue(job, "test failure", 1)
        controller.listDeadLetterMessages() shouldHaveSize 1

        controller.purgeDeadLetterMessage("job-1")
        controller.listDeadLetterMessages() shouldHaveSize 0
    }

    // --- Webhook integration tests ---

    test("dispatchJob dispatches webhook when callbackUrl is present") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob(callbackUrl = "https://callback.example.com/hook")
        val scraper = mockk<BaseScraper>()
        val scrapeResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("title" to "Test")
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns scrapeResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        controller.dispatchJob(job)

        verify { webhookDispatcher.dispatch("https://callback.example.com/hook", any()) }
    }

    test("routeToDeadLetterQueue dispatches failure webhook when callbackUrl present") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val webhookDispatcher = mocks["webhookDispatcher"] as WebhookDispatcher

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob(callbackUrl = "https://callback.example.com/hook")
        controller.routeToDeadLetterQueue(job, "final failure", 3)

        verify { webhookDispatcher.dispatchFailure("https://callback.example.com/hook", any()) }
    }

    // --- Metrics tests ---

    test("dispatchJob emits success metric on successful scrape") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val circuitBreaker = mocks["circuitBreaker"] as CircuitBreakerManager
        val scraperFactory = mocks["scraperFactory"] as ScraperFactory
        val metricsCollector = mocks["metricsCollector"] as MetricsCollector

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        val scraper = mockk<BaseScraper>()
        val scrapeResult = ScrapeResult(
            jobId = job.jobId, siteId = job.siteId,
            sourceUrl = job.targetUrl, extractedAt = Instant.now(),
            data = mapOf("title" to "Test")
        )

        every { circuitBreaker.checkState("example.com") } returns CircuitState.CLOSED
        every { scraperFactory.createScraper("example") } returns scraper
        every { scraper.execute(any()) } returns scrapeResult
        every { circuitBreaker.recordSuccess("example.com") } just Runs

        controller.dispatchJob(job)

        verify { metricsCollector.emitSuccess("job-1", "example", any()) }
    }

    test("routeToDeadLetterQueue emits failure metric") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val metricsCollector = mocks["metricsCollector"] as MetricsCollector

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val job = sampleJob()
        controller.routeToDeadLetterQueue(job, "test failure", 2)

        verify { metricsCollector.emitFailure("job-1", "example", "test failure", 2) }
    }

    // --- publishResult test ---

    test("publishResult serializes and publishes to results queue") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val result = NormalizedScrapeResult(
            jobId = "job-1", siteId = "example",
            sourceUrl = "https://example.com/page",
            extractionTimestamp = Instant.parse("2024-01-01T00:00:00Z"),
            scraperVersion = "1.0.0",
            data = mapOf("title" to "Test")
        )

        controller.publishResult(result)

        val publishSlot = slot<ByteArray>()
        verify { channel.basicPublish("", "scrape_results", null, capture(publishSlot)) }
        val deserialized = JsonMapper.mapper.readValue(publishSlot.captured, NormalizedScrapeResult::class.java)
        deserialized.jobId shouldBe "job-1"
        deserialized.data["title"] shouldBe "Test"
    }

    // --- Graceful shutdown test ---

    test("shutdown stops and logs completion") {
        val mocks = createMocks()
        val controller = createController(mocks)
        val channel = mocks["channel"] as Channel
        val connection = mocks["connection"] as Connection

        val channelField = ScraperController::class.java.getDeclaredField("channel")
        channelField.isAccessible = true
        channelField.set(controller, channel)

        val connectionField = ScraperController::class.java.getDeclaredField("connection")
        connectionField.isAccessible = true
        connectionField.set(controller, connection)

        controller.shutdown()

        verify { channel.close() }
        verify { connection.close() }
    }
})
