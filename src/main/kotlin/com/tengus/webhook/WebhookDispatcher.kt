package com.tengus.webhook

import com.tengus.config.WebhookConfig
import com.tengus.model.JobFailureNotification
import com.tengus.model.NormalizedScrapeResult
import com.tengus.serialization.JsonMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Delivers scrape results and failure notifications to callback URLs via HTTP POST.
 * Includes HMAC-SHA256 signing and retry with exponential backoff.
 *
 * Validates: Requirements 26.2, 26.3, 26.4, 26.5, 26.6
 */
class WebhookDispatcher(
    private val config: WebhookConfig,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {
    private val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)

    /**
     * Sends an HTTP POST with the serialized NormalizedScrapeResult as JSON body
     * to the given callback URL. Includes an X-Signature HMAC header.
     * Retries on 4xx/5xx with exponential backoff up to config.maxRetries.
     *
     * Validates: Requirements 26.2, 26.3, 26.4, 26.5
     */
    fun dispatch(callbackUrl: String, payload: NormalizedScrapeResult) {
        val body = JsonMapper.mapper.writeValueAsBytes(payload)
        sendWithRetry(callbackUrl, body, payload.jobId)
    }

    /**
     * Sends an HTTP POST with the serialized JobFailureNotification as JSON body
     * to the given callback URL. Includes an X-Signature HMAC header.
     * Retries on 4xx/5xx with exponential backoff up to config.maxRetries.
     *
     * Validates: Requirements 26.5, 26.6
     */
    fun dispatchFailure(callbackUrl: String, failure: JobFailureNotification) {
        val body = JsonMapper.mapper.writeValueAsBytes(failure)
        sendWithRetry(callbackUrl, body, failure.jobId)
    }

    /**
     * Computes HMAC-SHA256 over the given body bytes using the configured secret,
     * returning the result as a lowercase hex-encoded string.
     *
     * Validates: Requirement 26.3
     */
    fun computeHmac(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(config.hmacSecret.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun sendWithRetry(callbackUrl: String, body: ByteArray, jobId: String) {
        val signature = computeHmac(body)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(callbackUrl))
            .header("Content-Type", "application/json")
            .header("X-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        var lastStatusCode = 0
        for (attempt in 0..config.maxRetries) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            lastStatusCode = response.statusCode()

            if (lastStatusCode in 200..399) {
                return
            }

            if (attempt < config.maxRetries) {
                val delayMs = config.baseDelayMs * (1L shl attempt)
                Thread.sleep(delayMs)
            }
        }

        logger.warn(
            "Webhook delivery failed after all retries: jobId={}, callbackUrl={}, finalStatus={}",
            jobId,
            callbackUrl,
            lastStatusCode
        )
    }
}
