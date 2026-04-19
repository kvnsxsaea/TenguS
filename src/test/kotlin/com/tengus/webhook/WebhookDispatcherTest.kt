package com.tengus.webhook

import com.tengus.config.WebhookConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class WebhookDispatcherTest : FunSpec({

    val config = WebhookConfig(
        hmacSecret = "test-secret-key",
        maxRetries = 3,
        baseDelayMs = 100L
    )
    val dispatcher = WebhookDispatcher(config)

    test("computeHmac produces consistent result for same input") {
        val body = """{"jobId":"j1","siteId":"example"}""".toByteArray()
        val hmac1 = dispatcher.computeHmac(body)
        val hmac2 = dispatcher.computeHmac(body)
        hmac1 shouldBe hmac2
    }

    test("computeHmac produces different results for different inputs") {
        val body1 = """{"jobId":"j1"}""".toByteArray()
        val body2 = """{"jobId":"j2"}""".toByteArray()
        val hmac1 = dispatcher.computeHmac(body1)
        val hmac2 = dispatcher.computeHmac(body2)
        hmac1 shouldNotBe hmac2
    }

    test("computeHmac produces hex-encoded string of 64 characters for SHA256") {
        val body = "hello world".toByteArray()
        val hmac = dispatcher.computeHmac(body)
        hmac.length shouldBe 64
        hmac shouldMatch Regex("^[0-9a-f]{64}$")
    }
})
