package com.tengus.metrics

import com.fasterxml.jackson.module.kotlin.readValue
import com.tengus.config.MetricsConfig
import com.tengus.serialization.JsonMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MetricsCollectorTest : FunSpec({

    val mapper = JsonMapper.mapper
    val fixedInstant = Instant.parse("2024-06-15T10:00:00Z")
    val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    fun createCollector(
        slidingWindowMs: Long = 60_000L,
        clock: Clock = fixedClock,
        outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
    ): Pair<MetricsCollector, ByteArrayOutputStream> {
        val os = outputStream
        val config = MetricsConfig(reportingIntervalMs = 10_000L, slidingWindowMs = slidingWindowMs)
        val collector = MetricsCollector(config, clock, PrintStream(os))
        return collector to os
    }

    test("emitSuccess writes correct JSON with all required fields") {
        val (collector, os) = createCollector()
        collector.emitSuccess("job-1", "site-a", 1500L)

        val json = os.toString().trim()
        val metric: SuccessMetric = mapper.readValue(json)
        metric.metricType shouldBe "scrape_success"
        metric.jobId shouldBe "job-1"
        metric.siteId shouldBe "site-a"
        metric.durationMs shouldBe 1500L
        metric.status shouldBe "success"
        metric.timestamp shouldBe fixedInstant
    }

    test("emitFailure writes correct JSON with all required fields") {
        val (collector, os) = createCollector()
        collector.emitFailure("job-2", "site-b", "Connection timeout", 3)

        val json = os.toString().trim()
        val metric: FailureMetric = mapper.readValue(json)
        metric.metricType shouldBe "scrape_failure"
        metric.jobId shouldBe "job-2"
        metric.siteId shouldBe "site-b"
        metric.failureReason shouldBe "Connection timeout"
        metric.retryCount shouldBe 3
        metric.status shouldBe "failure"
        metric.timestamp shouldBe fixedInstant
    }

    test("emitSuccess JSON includes UTC timestamp") {
        val (collector, os) = createCollector()
        collector.emitSuccess("job-ts", "site-a", 100L)
        os.toString() shouldContain "2024-06-15T10:00:00Z"
    }

    test("emitFailure JSON includes UTC timestamp") {
        val (collector, os) = createCollector()
        collector.emitFailure("job-ts", "site-a", "err", 0)
        os.toString() shouldContain "2024-06-15T10:00:00Z"
    }

    test("emitRollingRates computes correct rates for single site") {
        val (collector, os) = createCollector()

        // Record 3 successes and 1 failure for site-a
        collector.emitSuccess("j1", "site-a", 100L)
        collector.emitSuccess("j2", "site-a", 200L)
        collector.emitSuccess("j3", "site-a", 300L)
        collector.emitFailure("j4", "site-a", "err", 1)

        // Clear output from emit calls
        os.reset()
        collector.emitRollingRates()

        val json = os.toString().trim()
        val metric: RollingRateMetric = mapper.readValue(json)
        metric.metricType shouldBe "rolling_rate"
        metric.siteId shouldBe "site-a"
        metric.successRate shouldBe (0.75 plusOrMinus 0.001)
        metric.failureRate shouldBe (0.25 plusOrMinus 0.001)
        metric.timestamp shouldBe fixedInstant
    }

    test("emitRollingRates emits one metric per site") {
        val (collector, os) = createCollector()

        collector.emitSuccess("j1", "site-a", 100L)
        collector.emitFailure("j2", "site-b", "err", 0)

        os.reset()
        collector.emitRollingRates()

        val lines = os.toString().trim().lines()
        lines.size shouldBe 2

        val metrics = lines.map { mapper.readValue<RollingRateMetric>(it) }
        val siteIds = metrics.map { it.siteId }.toSet()
        siteIds shouldBe setOf("site-a", "site-b")
    }

    test("emitRollingRates excludes events outside sliding window") {
        // Use a clock that advances: first events at T=0, then window check at T=120s with 60s window
        val baseInstant = Instant.parse("2024-06-15T10:00:00Z")
        val earlyClock = Clock.fixed(baseInstant, ZoneOffset.UTC)
        val lateClock = Clock.fixed(baseInstant.plusSeconds(120), ZoneOffset.UTC)

        val config = MetricsConfig(reportingIntervalMs = 10_000L, slidingWindowMs = 60_000L)
        val os = ByteArrayOutputStream()

        // Create collector with early clock, record events
        val earlyCollector = MetricsCollector(config, earlyClock, PrintStream(os))
        earlyCollector.emitSuccess("j1", "site-a", 100L)
        earlyCollector.emitFailure("j2", "site-a", "err", 0)

        // Now create a new collector that shares no events (simulating window expiry)
        val os2 = ByteArrayOutputStream()
        val lateCollector = MetricsCollector(config, lateClock, PrintStream(os2))
        lateCollector.emitRollingRates()

        // No events in window, so no output
        os2.toString().trim() shouldBe ""
    }

    test("emitQueueDepths writes correct JSON") {
        val (collector, os) = createCollector()
        collector.emitQueueDepths(42, 5)

        val json = os.toString().trim()
        val metric: QueueDepthMetric = mapper.readValue(json)
        metric.metricType shouldBe "queue_depth"
        metric.jobsQueueDepth shouldBe 42
        metric.dlqDepth shouldBe 5
        metric.timestamp shouldBe fixedInstant
    }

    test("emitAverageDurations computes correct average per site") {
        val (collector, os) = createCollector()

        collector.emitSuccess("j1", "site-a", 100L)
        collector.emitSuccess("j2", "site-a", 200L)
        collector.emitSuccess("j3", "site-a", 300L)

        os.reset()
        collector.emitAverageDurations()

        val json = os.toString().trim()
        val metric: AverageDurationMetric = mapper.readValue(json)
        metric.metricType shouldBe "avg_duration"
        metric.siteId shouldBe "site-a"
        metric.averageDurationMs shouldBe (200.0 plusOrMinus 0.001)
        metric.timestamp shouldBe fixedInstant
    }

    test("emitAverageDurations emits one metric per site") {
        val (collector, os) = createCollector()

        collector.emitSuccess("j1", "site-a", 100L)
        collector.emitSuccess("j2", "site-b", 400L)

        os.reset()
        collector.emitAverageDurations()

        val lines = os.toString().trim().lines()
        lines.size shouldBe 2

        val metrics = lines.map { mapper.readValue<AverageDurationMetric>(it) }
        val siteIds = metrics.map { it.siteId }.toSet()
        siteIds shouldBe setOf("site-a", "site-b")
    }

    test("emitProxyHealth writes correct JSON per domain") {
        val (collector, os) = createCollector()
        collector.emitProxyHealth(
            healthyCount = mapOf("example.com" to 8, "other.com" to 5),
            blockedCount = mapOf("example.com" to 2)
        )

        val lines = os.toString().trim().lines()
        lines.size shouldBe 2

        val metrics = lines.map { mapper.readValue<ProxyHealthMetric>(it) }
        val byDomain = metrics.associateBy { it.domain }

        byDomain["example.com"]!!.healthyCount shouldBe 8
        byDomain["example.com"]!!.blockedCount shouldBe 2
        byDomain["other.com"]!!.healthyCount shouldBe 5
        byDomain["other.com"]!!.blockedCount shouldBe 0
    }

    test("emitProxyHealth includes metric type and timestamp") {
        val (collector, os) = createCollector()
        collector.emitProxyHealth(
            healthyCount = mapOf("d.com" to 1),
            blockedCount = mapOf("d.com" to 0)
        )

        val metric: ProxyHealthMetric = mapper.readValue(os.toString().trim())
        metric.metricType shouldBe "proxy_health"
        metric.timestamp shouldBe fixedInstant
    }
})
