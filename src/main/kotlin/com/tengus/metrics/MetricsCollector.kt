package com.tengus.metrics

import com.tengus.config.MetricsConfig
import com.tengus.serialization.JsonMapper
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Emits structured JSON metrics to stdout for observability.
 *
 * Tracks success/failure events internally for rolling rate and average duration computation.
 * Uses an injectable [Clock] and [PrintStream] for testability.
 *
 * Validates: Requirements 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 20.7, 27.7
 */
class MetricsCollector(
    private val config: MetricsConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val output: PrintStream = System.out
) {
    private data class SuccessEvent(val siteId: String, val durationMs: Long, val timestamp: Long)
    private data class FailureEvent(val siteId: String, val timestamp: Long)

    private val successEvents = CopyOnWriteArrayList<SuccessEvent>()
    private val failureEvents = CopyOnWriteArrayList<FailureEvent>()

    /**
     * Emit a success metric and record the event for rolling computations.
     */
    fun emitSuccess(jobId: String, siteId: String, durationMs: Long) {
        val now = Instant.now(clock)
        successEvents.add(SuccessEvent(siteId, durationMs, now.toEpochMilli()))
        val metric = SuccessMetric(
            jobId = jobId,
            siteId = siteId,
            durationMs = durationMs,
            timestamp = now
        )
        output.println(JsonMapper.mapper.writeValueAsString(metric))
    }

    /**
     * Emit a failure metric and record the event for rolling computations.
     */
    fun emitFailure(jobId: String, siteId: String, reason: String, retryCount: Int) {
        val now = Instant.now(clock)
        failureEvents.add(FailureEvent(siteId, now.toEpochMilli()))
        val metric = FailureMetric(
            jobId = jobId,
            siteId = siteId,
            failureReason = reason,
            retryCount = retryCount,
            timestamp = now
        )
        output.println(JsonMapper.mapper.writeValueAsString(metric))
    }

    /**
     * Compute and emit rolling success/failure rates per site over the sliding window.
     */
    fun emitRollingRates() {
        val now = Instant.now(clock)
        val windowStart = now.toEpochMilli() - config.slidingWindowMs

        val recentSuccesses = successEvents.filter { it.timestamp >= windowStart }
        val recentFailures = failureEvents.filter { it.timestamp >= windowStart }

        val siteIds = (recentSuccesses.map { it.siteId } + recentFailures.map { it.siteId }).toSet()

        for (siteId in siteIds) {
            val successes = recentSuccesses.count { it.siteId == siteId }
            val failures = recentFailures.count { it.siteId == siteId }
            val total = successes + failures
            val successRate = if (total > 0) successes.toDouble() / total else 0.0
            val failureRate = if (total > 0) failures.toDouble() / total else 0.0

            val metric = RollingRateMetric(
                siteId = siteId,
                successRate = successRate,
                failureRate = failureRate,
                timestamp = now
            )
            output.println(JsonMapper.mapper.writeValueAsString(metric))
        }
    }

    /**
     * Emit queue depth metrics for the jobs queue and dead-letter queue.
     */
    fun emitQueueDepths(jobsQueueDepth: Int, dlqDepth: Int) {
        val metric = QueueDepthMetric(
            jobsQueueDepth = jobsQueueDepth,
            dlqDepth = dlqDepth,
            timestamp = Instant.now(clock)
        )
        output.println(JsonMapper.mapper.writeValueAsString(metric))
    }

    /**
     * Compute and emit average scrape duration per site over the sliding window.
     */
    fun emitAverageDurations() {
        val now = Instant.now(clock)
        val windowStart = now.toEpochMilli() - config.slidingWindowMs

        val recentSuccesses = successEvents.filter { it.timestamp >= windowStart }
        val bySite = recentSuccesses.groupBy { it.siteId }

        for ((siteId, events) in bySite) {
            val avg = events.map { it.durationMs }.average()
            val metric = AverageDurationMetric(
                siteId = siteId,
                averageDurationMs = avg,
                timestamp = now
            )
            output.println(JsonMapper.mapper.writeValueAsString(metric))
        }
    }

    /**
     * Emit proxy health metrics with healthy/blocked counts per domain.
     */
    fun emitProxyHealth(healthyCount: Map<String, Int>, blockedCount: Map<String, Int>) {
        val now = Instant.now(clock)
        val domains = (healthyCount.keys + blockedCount.keys).toSet()

        for (domain in domains) {
            val metric = ProxyHealthMetric(
                domain = domain,
                healthyCount = healthyCount.getOrDefault(domain, 0),
                blockedCount = blockedCount.getOrDefault(domain, 0),
                timestamp = now
            )
            output.println(JsonMapper.mapper.writeValueAsString(metric))
        }
    }
}
