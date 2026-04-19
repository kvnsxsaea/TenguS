package com.tengus.controller

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Delivery
import com.tengus.config.AppConfig
import com.tengus.metrics.MetricsCollector
import com.tengus.model.DeadLetterEntry
import com.tengus.model.JobFailureNotification
import com.tengus.model.NormalizedScrapeResult
import com.tengus.model.ScrapeJob
import com.tengus.ratelimit.RateLimiter
import com.tengus.resilience.CircuitBreakerManager
import com.tengus.resilience.CircuitState
import com.tengus.resilience.RetryPolicyResolver
import com.tengus.result.ResultNormalizer
import com.tengus.scraper.ScraperFactory
import com.tengus.serialization.JsonMapper
import com.tengus.webhook.WebhookDispatcher
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central orchestrator that consumes scrape jobs from RabbitMQ, coordinates
 * all components (circuit breaker, rate limiter, scraper factory, result
 * normalizer, webhook dispatcher, metrics), and manages the scrape lifecycle
 * including retry, DLQ routing, and graceful shutdown.
 *
 * Validates: Requirements 2.2, 2.4, 3.2, 3.3, 3.4, 4.1, 12.3, 14.1, 14.4, 14.5,
 *            15.1, 15.2, 15.3, 15.4, 18.1, 20.1, 20.2, 20.3, 20.4, 20.5, 20.6,
 *            21.3, 21.5, 22.2, 22.3, 23.1, 23.2, 23.3, 23.4, 23.5, 23.6, 26.2, 26.6
 */
class ScraperController(
    private val connectionFactory: ConnectionFactory,
    private val scraperFactory: ScraperFactory,
    private val circuitBreaker: CircuitBreakerManager,
    private val rateLimiter: RateLimiter,
    private val retryPolicyResolver: RetryPolicyResolver,
    private val resultNormalizer: ResultNormalizer,
    private val webhookDispatcher: WebhookDispatcher,
    private val metricsCollector: MetricsCollector,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(ScraperController::class.java)

    private var connection: Connection? = null
    private var channel: Channel? = null
    private val consumerTag = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private val shuttingDown = AtomicBoolean(false)
    private val inFlightJobs = ConcurrentHashMap<String, Thread>()
    private val shutdownLatch = CountDownLatch(1)

    // In-memory DLQ store (queue-based DLQ entries are also tracked here for list/replay/purge)
    private val deadLetterStore = ConcurrentHashMap<String, DeadLetterEntry>()

    /**
     * Connects to RabbitMQ, declares required queues, registers a JVM shutdown
     * hook, and begins consuming from the jobs queue one message at a time (FIFO).
     */
    fun start() {
        connection = connectionFactory.newConnection()
        channel = connection!!.createChannel().also { ch ->
            ch.queueDeclare(config.rabbitmq.jobsQueue, true, false, false, null)
            ch.queueDeclare(config.rabbitmq.resultsQueue, true, false, false, null)
            ch.queueDeclare(config.rabbitmq.dlqQueue, true, false, false, null)
            // Consume one at a time (prefetch = 1) for FIFO ordering
            ch.basicQos(1)
        }

        // Register JVM shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread({ shutdown() }, "scraper-shutdown-hook"))

        val tag = channel!!.basicConsume(
            config.rabbitmq.jobsQueue,
            false, // manual ack
            { _, delivery -> handleMessage(delivery) },
            { _ -> logger.info("Consumer cancelled") }
        )
        consumerTag.set(tag)
        logger.info("ScraperController started, consuming from queue '{}'", config.rabbitmq.jobsQueue)
    }

    /**
     * Graceful shutdown: stop consuming, wait for in-flight jobs, force-cancel on timeout.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return

        val gracePeriod = config.shutdown.gracePeriodSeconds.toLong()
        logger.info(
            "Shutdown initiated: inFlightJobs={}, gracePeriodSeconds={}",
            inFlightJobs.size, gracePeriod
        )

        // Stop consuming new messages
        try {
            consumerTag.get()?.let { tag ->
                channel?.basicCancel(tag)
            }
        } catch (e: Exception) {
            logger.warn("Error cancelling consumer during shutdown: {}", e.message)
        }

        // Wait for in-flight jobs to complete within grace period
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(gracePeriod)
        while (inFlightJobs.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
        }

        // Force-cancel remaining jobs
        if (inFlightJobs.isNotEmpty()) {
            logger.warn("Force-cancelling {} remaining in-flight jobs", inFlightJobs.size)
            inFlightJobs.forEach { (jobId, thread) ->
                try {
                    thread.interrupt()
                } catch (e: Exception) {
                    logger.warn("Error interrupting job {}: {}", jobId, e.message)
                }
            }
        }

        // Close RabbitMQ resources
        try { channel?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}

        logger.info("Shutdown complete")
        shutdownLatch.countDown()
    }

    /**
     * Handles a raw RabbitMQ delivery: deserializes the ScrapeJob from JSON,
     * then dispatches it. Rejects and logs on deserialization failure.
     */
    fun handleMessage(delivery: Delivery) {
        if (shuttingDown.get()) {
            // Nack and requeue during shutdown
            try { channel?.basicNack(delivery.envelope.deliveryTag, false, true) } catch (_: Exception) {}
            return
        }

        val body = String(delivery.body)
        val job: ScrapeJob
        try {
            job = JsonMapper.mapper.readValue(body, ScrapeJob::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize ScrapeJob: {}, raw={}", e.message, body)
            try { channel?.basicReject(delivery.envelope.deliveryTag, false) } catch (_: Exception) {}
            return
        }

        logger.info("Job consumed: jobId={}, siteId={}, targetUrl={}", job.jobId, job.siteId, job.targetUrl)

        val currentThread = Thread.currentThread()
        inFlightJobs[job.jobId] = currentThread
        try {
            dispatchJob(job)
            channel?.basicAck(delivery.envelope.deliveryTag, false)
        } catch (e: Exception) {
            handleJobFailure(job, e, delivery)
        } finally {
            inFlightJobs.remove(job.jobId)
        }
    }

    /**
     * Dispatches a scrape job through the full pipeline:
     * circuit breaker check → rate limiter → scraper factory → execute → normalize → publish.
     */
    fun dispatchJob(job: ScrapeJob) {
        val domain = URI(job.targetUrl).host

        // Check circuit breaker state
        val cbState = circuitBreaker.checkState(domain)
        if (cbState == CircuitState.OPEN) {
            logger.warn("Circuit breaker OPEN for domain '{}', re-enqueueing jobId={}", domain, job.jobId)
            reEnqueueWithDelay(job)
            return
        }

        // Acquire rate limiter permit (blocks until available)
        rateLimiter.acquire(domain)

        // Get scraper from factory and execute
        val scraper = scraperFactory.createScraper(job.siteId)
        val startTime = System.currentTimeMillis()

        try {
            val result = scraper.execute(job)

            // Record circuit breaker success
            circuitBreaker.recordSuccess(domain)

            // Normalize result
            val normalized = resultNormalizer.normalize(result, job)

            // Warn on empty data
            if (normalized.data.isEmpty()) {
                logger.warn("Empty data payload for jobId={}, siteId={}", job.jobId, job.siteId)
            }

            // Publish result
            publishResult(normalized)

            // Webhook dispatch if callback URL present
            if (!job.callbackUrl.isNullOrBlank()) {
                try {
                    webhookDispatcher.dispatch(job.callbackUrl, normalized)
                } catch (e: Exception) {
                    logger.warn("Webhook dispatch failed for jobId={}: {}", job.jobId, e.message)
                }
            }

            // Emit success metric
            val durationMs = System.currentTimeMillis() - startTime
            metricsCollector.emitSuccess(job.jobId, job.siteId, durationMs)
        } catch (e: Exception) {
            // Record circuit breaker failure
            circuitBreaker.recordFailure(domain)
            throw e
        }
    }

    /**
     * Serializes a [NormalizedScrapeResult] and publishes it to the results queue.
     */
    fun publishResult(result: NormalizedScrapeResult) {
        val json = JsonMapper.mapper.writeValueAsBytes(result)
        channel?.basicPublish("", config.rabbitmq.resultsQueue, null, json)
        logger.info("Result published for jobId={}", result.jobId)
    }

    /**
     * Routes a failed job to the dead-letter queue, preserving original payload,
     * failure reason, retry count, and UTC timestamp.
     */
    fun routeToDeadLetterQueue(job: ScrapeJob, reason: String, retryCount: Int) {
        val entry = DeadLetterEntry(
            jobId = job.jobId,
            siteId = job.siteId,
            originalPayload = job,
            failureReason = reason,
            retryCount = retryCount,
            enqueuedAt = Instant.now()
        )
        val json = JsonMapper.mapper.writeValueAsBytes(entry)
        channel?.basicPublish("", config.rabbitmq.dlqQueue, null, json)
        deadLetterStore[job.jobId] = entry
        logger.warn("Job routed to DLQ: jobId={}, reason={}, retryCount={}", job.jobId, reason, retryCount)

        // Emit failure metric
        metricsCollector.emitFailure(job.jobId, job.siteId, reason, retryCount)

        // Dispatch failure webhook if callback URL present
        if (!job.callbackUrl.isNullOrBlank()) {
            try {
                val notification = JobFailureNotification(
                    jobId = job.jobId,
                    siteId = job.siteId,
                    failureReason = reason,
                    timestamp = Instant.now()
                )
                webhookDispatcher.dispatchFailure(job.callbackUrl, notification)
            } catch (e: Exception) {
                logger.warn("Failure webhook dispatch failed for jobId={}: {}", job.jobId, e.message)
            }
        }
    }

    /**
     * Lists all messages currently in the dead-letter store.
     */
    fun listDeadLetterMessages(): List<DeadLetterEntry> {
        return deadLetterStore.values.toList()
    }

    /**
     * Replays a specific dead-letter message back to the jobs queue with retry count reset to zero.
     */
    fun replayDeadLetterMessage(jobId: String) {
        val entry = deadLetterStore.remove(jobId)
            ?: throw IllegalArgumentException("No dead-letter entry found for jobId=$jobId")

        val replayedJob = entry.originalPayload.copy(retryCount = 0)
        val json = JsonMapper.mapper.writeValueAsBytes(replayedJob)
        channel?.basicPublish("", config.rabbitmq.jobsQueue, null, json)
        logger.info("Dead-letter message replayed: jobId={}", jobId)
    }

    /**
     * Purges a specific message from the dead-letter store by job ID.
     */
    fun purgeDeadLetterMessage(jobId: String) {
        val removed = deadLetterStore.remove(jobId)
        if (removed != null) {
            logger.info("Dead-letter message purged: jobId={}", jobId)
        } else {
            logger.warn("No dead-letter entry found to purge for jobId={}", jobId)
        }
    }

    /**
     * Handles a job failure by determining whether to retry or route to DLQ.
     * Retryable errors: network timeout, proxy failure, HTTP 429/5xx.
     * Unrecoverable errors go directly to DLQ.
     */
    private fun handleJobFailure(job: ScrapeJob, error: Exception, delivery: Delivery) {
        val reason = error.message ?: error::class.simpleName ?: "Unknown error"

        if (isRetryable(error)) {
            val strategy = retryPolicyResolver.resolve(job.siteId)
            val nextRetryCount = job.retryCount + 1

            if (nextRetryCount > strategy.maxRetries) {
                // Max retries exceeded → DLQ
                logger.warn(
                    "Max retries exceeded for jobId={}, routing to DLQ (retries={})",
                    job.jobId, job.retryCount
                )
                try { channel?.basicAck(delivery.envelope.deliveryTag, false) } catch (_: Exception) {}
                routeToDeadLetterQueue(job, reason, job.retryCount)
            } else {
                // Re-enqueue with incremented retry count
                val delay = retryPolicyResolver.computeDelay(strategy, nextRetryCount)
                logger.info(
                    "Retrying jobId={}, attempt={}/{}, delayMs={}",
                    job.jobId, nextRetryCount, strategy.maxRetries, delay
                )
                try { channel?.basicAck(delivery.envelope.deliveryTag, false) } catch (_: Exception) {}
                val retriedJob = job.copy(retryCount = nextRetryCount)
                val json = JsonMapper.mapper.writeValueAsBytes(retriedJob)
                // Sleep for backoff delay before re-enqueue
                if (delay > 0) {
                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                }
                channel?.basicPublish("", config.rabbitmq.jobsQueue, null, json)
            }
        } else {
            // Unrecoverable error → nack and route to DLQ
            logger.error("Unrecoverable error for jobId={}: {}", job.jobId, reason)
            try { channel?.basicNack(delivery.envelope.deliveryTag, false, false) } catch (_: Exception) {}
            routeToDeadLetterQueue(job, reason, job.retryCount)
        }
    }

    /**
     * Re-enqueues a job with a short delay (used when circuit breaker is open).
     */
    private fun reEnqueueWithDelay(job: ScrapeJob) {
        val json = JsonMapper.mapper.writeValueAsBytes(job)
        // Brief delay before re-enqueue to avoid tight loop
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        channel?.basicPublish("", config.rabbitmq.jobsQueue, null, json)
    }

    /**
     * Determines if an error is retryable (network timeout, proxy failure, HTTP 429/5xx).
     */
    private fun isRetryable(error: Exception): Boolean {
        val message = (error.message ?: "").lowercase()
        return message.contains("timeout") ||
            message.contains("proxy") ||
            message.contains("429") ||
            message.contains("5xx") ||
            message.contains("500") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504") ||
            error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException
    }
}
