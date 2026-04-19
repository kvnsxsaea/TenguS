package com.tengus.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
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
})
