package com.tengus.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.tengus.metrics.AverageDurationMetric
import com.tengus.metrics.FailureMetric
import com.tengus.metrics.ProxyHealthMetric
import com.tengus.metrics.QueueDepthMetric
import com.tengus.metrics.RollingRateMetric
import com.tengus.metrics.SuccessMetric
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

class JsonMapperTest : FunSpec({

    val mapper = JsonMapper.mapper

    test("singleton returns the same instance") {
        JsonMapper.mapper shouldBe JsonMapper.mapper
    }

    test("FAIL_ON_UNKNOWN_PROPERTIES is disabled") {
        mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) shouldBe false
    }

    test("WRITE_DATES_AS_TIMESTAMPS is disabled") {
        mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) shouldBe false
    }

    test("serializes Instant as ISO-8601 string") {
        val instant = Instant.parse("2024-06-15T10:30:00Z")
        val job = ScrapeJob(
            jobId = "j1",
            targetUrl = "https://example.com",
            siteId = "example",
            createdAt = instant
        )
        val json = mapper.writeValueAsString(job)
        json shouldContain "\"2024-06-15T10:30:00Z\""
        json shouldNotContain "1718444" // no epoch millis
    }

    test("deserializes unknown properties without error") {
        val json = """{"jobId":"j1","targetUrl":"https://example.com","siteId":"ex","unknownField":"ignored"}"""
        val job: ScrapeJob = mapper.readValue(json)
        job.jobId shouldBe "j1"
    }

    test("ScrapeJob round-trip serialization") {
        val original = ScrapeJob(
            jobId = "rt-1",
            targetUrl = "https://example.com/page",
            siteId = "example",
            callbackUrl = "https://callback.test/hook",
            parameters = mapOf("key" to "value"),
            retryCount = 2,
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: ScrapeJob = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("ScrapeResult round-trip serialization") {
        val original = ScrapeResult(
            jobId = "sr-1",
            siteId = "example",
            sourceUrl = "https://example.com",
            extractedAt = Instant.parse("2024-03-10T12:00:00Z"),
            data = mapOf("title" to "Test Page")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: ScrapeResult = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("SuccessMetric round-trip serialization") {
        val original = SuccessMetric(
            jobId = "sm-1",
            siteId = "example",
            durationMs = 1234L,
            timestamp = Instant.parse("2024-06-15T10:30:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: SuccessMetric = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("FailureMetric round-trip serialization") {
        val original = FailureMetric(
            jobId = "fm-1",
            siteId = "example",
            failureReason = "Connection timeout",
            retryCount = 3,
            timestamp = Instant.parse("2024-06-15T11:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: FailureMetric = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("RollingRateMetric round-trip serialization") {
        val original = RollingRateMetric(
            siteId = "example",
            successRate = 0.95,
            failureRate = 0.05,
            timestamp = Instant.parse("2024-06-15T12:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: RollingRateMetric = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("QueueDepthMetric round-trip serialization") {
        val original = QueueDepthMetric(
            jobsQueueDepth = 42,
            dlqDepth = 3,
            timestamp = Instant.parse("2024-06-15T13:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: QueueDepthMetric = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("AverageDurationMetric round-trip serialization") {
        val original = AverageDurationMetric(
            siteId = "example",
            averageDurationMs = 2345.67,
            timestamp = Instant.parse("2024-06-15T14:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: AverageDurationMetric = mapper.readValue(json)
        deserialized shouldBe original
    }

    test("ProxyHealthMetric round-trip serialization") {
        val original = ProxyHealthMetric(
            domain = "example.com",
            healthyCount = 8,
            blockedCount = 2,
            timestamp = Instant.parse("2024-06-15T15:00:00Z")
        )
        val json = mapper.writeValueAsString(original)
        val deserialized: ProxyHealthMetric = mapper.readValue(json)
        deserialized shouldBe original
    }
})
