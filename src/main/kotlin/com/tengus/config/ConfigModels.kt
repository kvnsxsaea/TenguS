package com.tengus.config

/**
 * Placeholder types for domain models referenced by configuration.
 * These will be moved to their proper packages in later tasks.
 */

data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val protocol: String = "http"
)

data class WeightedUserAgent(
    val userAgent: String,
    val weight: Double,
    val browserFamily: String,
    val browserVersion: String
)

data class ViewportSize(val width: Int, val height: Int)

enum class BackoffType { FIXED, LINEAR, EXPONENTIAL }

data class RetryStrategy(
    val maxRetries: Int,
    val backoffType: BackoffType,
    val baseDelayMs: Long,
    val jitterRangeMs: LongRange
)

// --- Top-level application config ---

data class AppConfig(
    val rabbitmq: RabbitMqConfig,
    val rateLimiter: RateLimitConfig,
    val proxy: ProxyConfig,
    val userAgent: UserAgentConfig,
    val retry: RetryConfig,
    val humanBehavior: HumanBehaviorConfig,
    val stealth: StealthConfig,
    val circuitBreaker: CircuitBreakerConfig,
    val warmup: WarmupConfig,
    val webhook: WebhookConfig,
    val metrics: MetricsConfig,
    val shutdown: ShutdownConfig,
    val scraperRegistry: ScraperRegistryConfig
)

data class RabbitMqConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val jobsQueue: String,
    val resultsQueue: String,
    val dlqQueue: String
)

data class RateLimitConfig(
    val defaultMaxRequests: Int,
    val defaultWindowMs: Long,
    val domainOverrides: Map<String, DomainRateLimit> = emptyMap()
)

data class DomainRateLimit(val maxRequests: Int, val windowMs: Long)

data class ProxyConfig(
    val endpoints: List<ProxyEndpoint>,
    val connectTimeoutMs: Long,
    val healthCheckIntervalMs: Long
)

data class ProxyHealthConfig(
    val slidingWindowMs: Long,
    val failureRateThreshold: Double,
    val cooldownMs: Long
)

data class UserAgentConfig(
    val agents: List<WeightedUserAgent>
)

data class RetryConfig(
    val globalMaxRetries: Int,
    val globalBackoffType: BackoffType,
    val globalBaseDelayMs: Long,
    val globalJitterRangeMs: LongRange,
    val perSiteOverrides: Map<String, RetryStrategy> = emptyMap()
)

data class HumanBehaviorConfig(
    val initialDelayRange: LongRange = 1000L..5000L,
    val keystrokeDelayRange: LongRange = 50L..200L,
    val scrollPauseRange: LongRange = 300L..1500L,
    val interActionDelayRange: LongRange = 500L..3000L
)

data class StealthConfig(
    val viewports: List<ViewportSize>,
    val timezones: List<String>,
    val languages: List<String>,
    val platforms: List<String>,
    val webglVendors: List<String>,
    val webglRenderers: List<String>
)

data class CircuitBreakerConfig(
    val defaultFailureThreshold: Int,
    val defaultCooldownMs: Long,
    val domainOverrides: Map<String, DomainCircuitBreakerConfig> = emptyMap()
)

data class DomainCircuitBreakerConfig(
    val failureThreshold: Int,
    val cooldownMs: Long
)

data class WarmupConfig(
    val urls: List<String>,
    val minPages: Int,
    val maxPages: Int,
    val pageTimeoutMs: Long
)

data class WebhookConfig(
    val hmacSecret: String,
    val maxRetries: Int,
    val baseDelayMs: Long
)

data class MetricsConfig(
    val reportingIntervalMs: Long,
    val slidingWindowMs: Long
)

data class ShutdownConfig(
    val gracePeriodSeconds: Int
)

data class ScraperRegistryConfig(
    val scanPackage: String
)
