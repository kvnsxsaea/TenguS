package com.tengus.result

import com.fasterxml.jackson.module.kotlin.readValue
import com.tengus.model.NormalizedScrapeResult
import com.tengus.model.ScrapeJob
import com.tengus.model.ScrapeResult
import com.tengus.serialization.JsonMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Instant

class ResultNormalizerTest : FunSpec({

    val version = "1.0.0"
    val normalizer = ResultNormalizer(version)

    val now = Instant.parse("2024-06-15T10:30:00Z")

    val job = ScrapeJob(
        jobId = "job-1",
        targetUrl = "https://example.com/page",
        siteId = "example",
        createdAt = now
    )

    val result = ScrapeResult(
        jobId = "job-1",
        siteId = "example",
        sourceUrl = "https://example.com/page",
        extractedAt = now,
        data = mapOf("title" to "Test Page", "price" to 19.99)
    )

    test("normalize maps all fields correctly") {
        val normalized = normalizer.normalize(result, job)

        normalized.jobId shouldBe result.jobId
        normalized.siteId shouldBe result.siteId
        normalized.sourceUrl shouldBe result.sourceUrl
        normalized.extractionTimestamp shouldBe result.extractedAt
        normalized.scraperVersion shouldBe version
        normalized.data shouldBe result.data
    }

    test("normalize uses configured scraper version") {
        val customNormalizer = ResultNormalizer("2.5.0-beta")
        val normalized = customNormalizer.normalize(result, job)
        normalized.scraperVersion shouldBe "2.5.0-beta"
    }

    test("validate returns valid for a complete normalized result") {
        val normalized = normalizer.normalize(result, job)
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe true
        validation.errors shouldBe emptyList()
    }

    test("validate rejects blank jobId") {
        val normalized = NormalizedScrapeResult(
            jobId = "",
            siteId = "example",
            sourceUrl = "https://example.com",
            extractionTimestamp = now,
            scraperVersion = version,
            data = mapOf("key" to "value")
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors shouldContain "jobId must not be blank"
    }

    test("validate rejects blank siteId") {
        val normalized = NormalizedScrapeResult(
            jobId = "job-1",
            siteId = "  ",
            sourceUrl = "https://example.com",
            extractionTimestamp = now,
            scraperVersion = version,
            data = mapOf("key" to "value")
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors shouldContain "siteId must not be blank"
    }

    test("validate rejects blank sourceUrl") {
        val normalized = NormalizedScrapeResult(
            jobId = "job-1",
            siteId = "example",
            sourceUrl = "",
            extractionTimestamp = now,
            scraperVersion = version,
            data = mapOf("key" to "value")
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors shouldContain "sourceUrl must not be blank"
    }

    test("validate rejects blank scraperVersion") {
        val normalized = NormalizedScrapeResult(
            jobId = "job-1",
            siteId = "example",
            sourceUrl = "https://example.com",
            extractionTimestamp = now,
            scraperVersion = "",
            data = mapOf("key" to "value")
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors shouldContain "scraperVersion must not be blank"
    }

    test("validate rejects empty data map") {
        val normalized = NormalizedScrapeResult(
            jobId = "job-1",
            siteId = "example",
            sourceUrl = "https://example.com",
            extractionTimestamp = now,
            scraperVersion = version,
            data = emptyMap()
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors shouldContain "data must not be empty"
    }

    test("validate collects multiple errors") {
        val normalized = NormalizedScrapeResult(
            jobId = "",
            siteId = "",
            sourceUrl = "",
            extractionTimestamp = now,
            scraperVersion = "",
            data = emptyMap()
        )
        val validation = normalizer.validate(normalized)

        validation.valid shouldBe false
        validation.errors.size shouldBe 5
    }

    test("NormalizedScrapeResult JSON round-trip produces equivalent object") {
        val normalized = normalizer.normalize(result, job)
        val json = JsonMapper.mapper.writeValueAsString(normalized)
        val deserialized: NormalizedScrapeResult = JsonMapper.mapper.readValue(json)
        deserialized shouldBe normalized
    }
})
