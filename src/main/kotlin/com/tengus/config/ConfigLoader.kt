package com.tengus.config

import com.tengus.model.ProxyEndpoint
import com.tengus.model.ViewportSize
import com.tengus.model.WeightedUserAgent
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileNotFoundException

/**
 * Loads application configuration from a YAML file and maps it to [AppConfig].
 * Validates all required keys at startup and fails with descriptive errors
 * if anything is missing or invalid.
 */
class ConfigLoader {

    fun load(path: String): AppConfig {
        val file = File(path)
        if (!file.exists()) {
            throw ConfigurationException("Configuration file not found: $path")
        }
        val yaml = Yaml()
        val raw: Map<String, Any> = file.inputStream().use { yaml.load(it) }
            ?: throw ConfigurationException("Configuration file is empty: $path")

        return parseAppConfig(raw)
    }

    fun loadFromClasspath(resource: String = "application.yml"): AppConfig {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
            ?: throw ConfigurationException("Configuration resource not found on classpath: $resource")
        val yaml = Yaml()
        val raw: Map<String, Any> = stream.use { yaml.load(it) }
            ?: throw ConfigurationException("Configuration resource is empty: $resource")

        return parseAppConfig(raw)
    }

    private fun parseAppConfig(raw: Map<String, Any>): AppConfig {
        val errors = mutableListOf<String>()

        val rabbitmq = parseRabbitMqConfig(raw.section("rabbitmq", errors), errors)
        val rateLimiter = parseRateLimitConfig(raw.section("rateLimiter", errors), errors)
        val proxy = parseProxyConfig(raw.section("proxy", errors), errors)
        val userAgent = parseUserAgentConfig(raw.section("userAgent", errors), errors)
        val retry = parseRetryConfig(raw.section("retry", errors), errors)
        val humanBehavior = parseHumanBehaviorConfig(raw.sectionOrEmpty("humanBehavior"))
        val stealth = parseStealthConfig(raw.section("stealth", errors), errors)
        val circuitBreaker = parseCircuitBreakerConfig(raw.section("circuitBreaker", errors), errors)
        val warmup = parseWarmupConfig(raw.section("warmup", errors), errors)
        val webhook = parseWebhookConfig(raw.section("webhook", errors), errors)
        val metrics = parseMetricsConfig(raw.section("metrics", errors), errors)
        val shutdown = parseShutdownConfig(raw.section("shutdown", errors), errors)
        val scraperRegistry = parseScraperRegistryConfig(raw.section("scraperRegistry", errors), errors)

        if (errors.isNotEmpty()) {
            throw ConfigurationException(
                "Configuration validation failed:\n" + errors.joinToString("\n") { "  - $it" }
            )
        }

        return AppConfig(
            rabbitmq = rabbitmq!!,
            rateLimiter = rateLimiter!!,
            proxy = proxy!!,
            userAgent = userAgent!!,
            retry = retry!!,
            humanBehavior = humanBehavior,
            stealth = stealth!!,
            circuitBreaker = circuitBreaker!!,
            warmup = warmup!!,
            webhook = webhook!!,
            metrics = metrics!!,
            shutdown = shutdown!!,
            scraperRegistry = scraperRegistry!!
        )
    }

    // --- Section parsers ---

    private fun parseRabbitMqConfig(map: Map<String, Any>?, errors: MutableList<String>): RabbitMqConfig? {
        if (map == null) return null
        val prefix = "rabbitmq"
        val host = map.requireString("host", prefix, errors) ?: return null
        val port = map.requireInt("port", prefix, errors) ?: return null
        val username = map.requireString("username", prefix, errors) ?: return null
        val password = map.requireString("password", prefix, errors) ?: return null
        val jobsQueue = map.requireString("jobsQueue", prefix, errors) ?: return null
        val resultsQueue = map.requireString("resultsQueue", prefix, errors) ?: return null
        val dlqQueue = map.requireString("dlqQueue", prefix, errors) ?: return null

        if (port !in 1..65535) {
            errors.add("$prefix.port must be between 1 and 65535, got: $port")
            return null
        }

        return RabbitMqConfig(host, port, username, password, jobsQueue, resultsQueue, dlqQueue)
    }

    private fun parseRateLimitConfig(map: Map<String, Any>?, errors: MutableList<String>): RateLimitConfig? {
        if (map == null) return null
        val prefix = "rateLimiter"
        val defaultMaxRequests = map.requireInt("defaultMaxRequests", prefix, errors) ?: return null
        val defaultWindowMs = map.requireLong("defaultWindowMs", prefix, errors) ?: return null

        if (defaultMaxRequests <= 0) {
            errors.add("$prefix.defaultMaxRequests must be positive, got: $defaultMaxRequests")
            return null
        }
        if (defaultWindowMs <= 0) {
            errors.add("$prefix.defaultWindowMs must be positive, got: $defaultWindowMs")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val overridesRaw = map["domainOverrides"] as? Map<String, Map<String, Any>> ?: emptyMap()
        val domainOverrides = overridesRaw.mapValues { (domain, v) ->
            DomainRateLimit(
                maxRequests = v.requireInt("maxRequests", "$prefix.domainOverrides.$domain", errors) ?: 0,
                windowMs = v.requireLong("windowMs", "$prefix.domainOverrides.$domain", errors) ?: 0
            )
        }

        return RateLimitConfig(defaultMaxRequests, defaultWindowMs, domainOverrides)
    }

    private fun parseProxyConfig(map: Map<String, Any>?, errors: MutableList<String>): ProxyConfig? {
        if (map == null) return null
        val prefix = "proxy"
        val connectTimeoutMs = map.requireLong("connectTimeoutMs", prefix, errors) ?: return null
        val healthCheckIntervalMs = map.requireLong("healthCheckIntervalMs", prefix, errors) ?: return null

        @Suppress("UNCHECKED_CAST")
        val endpointsRaw = map["endpoints"] as? List<Map<String, Any>>
        if (endpointsRaw == null || endpointsRaw.isEmpty()) {
            errors.add("$prefix.endpoints is required and must not be empty")
            return null
        }

        val endpoints = endpointsRaw.mapIndexed { i, ep ->
            val epPrefix = "$prefix.endpoints[$i]"
            ProxyEndpoint(
                host = ep.requireString("host", epPrefix, errors) ?: "",
                port = ep.requireInt("port", epPrefix, errors) ?: 0,
                username = ep["username"] as? String,
                password = ep["password"] as? String,
                protocol = (ep["protocol"] as? String) ?: "http"
            )
        }

        if (connectTimeoutMs <= 0) {
            errors.add("$prefix.connectTimeoutMs must be positive, got: $connectTimeoutMs")
            return null
        }

        return ProxyConfig(endpoints, connectTimeoutMs, healthCheckIntervalMs)
    }

    private fun parseUserAgentConfig(map: Map<String, Any>?, errors: MutableList<String>): UserAgentConfig? {
        if (map == null) return null
        val prefix = "userAgent"

        @Suppress("UNCHECKED_CAST")
        val agentsRaw = map["agents"] as? List<Map<String, Any>>
        if (agentsRaw == null || agentsRaw.isEmpty()) {
            errors.add("$prefix.agents is required and must not be empty")
            return null
        }

        val agents = agentsRaw.mapIndexed { i, a ->
            val aPrefix = "$prefix.agents[$i]"
            WeightedUserAgent(
                userAgent = a.requireString("userAgent", aPrefix, errors) ?: "",
                weight = a.requireDouble("weight", aPrefix, errors) ?: 0.0,
                browserFamily = a.requireString("browserFamily", aPrefix, errors) ?: "",
                browserVersion = a.requireString("browserVersion", aPrefix, errors) ?: ""
            )
        }

        return UserAgentConfig(agents)
    }

    private fun parseRetryConfig(map: Map<String, Any>?, errors: MutableList<String>): RetryConfig? {
        if (map == null) return null
        val prefix = "retry"
        val globalMaxRetries = map.requireInt("globalMaxRetries", prefix, errors) ?: return null
        val globalBackoffTypeStr = map.requireString("globalBackoffType", prefix, errors) ?: return null
        val globalBaseDelayMs = map.requireLong("globalBaseDelayMs", prefix, errors) ?: return null

        val globalBackoffType = try {
            BackoffType.valueOf(globalBackoffTypeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            errors.add("$prefix.globalBackoffType must be one of FIXED, LINEAR, EXPONENTIAL, got: $globalBackoffTypeStr")
            return null
        }

        if (globalMaxRetries < 0) {
            errors.add("$prefix.globalMaxRetries must be non-negative, got: $globalMaxRetries")
            return null
        }
        if (globalBaseDelayMs <= 0) {
            errors.add("$prefix.globalBaseDelayMs must be positive, got: $globalBaseDelayMs")
            return null
        }

        val globalJitterRangeMs = map.requireLongRange("globalJitterRangeMs", prefix, errors) ?: return null

        @Suppress("UNCHECKED_CAST")
        val overridesRaw = map["perSiteOverrides"] as? Map<String, Map<String, Any>> ?: emptyMap()
        val perSiteOverrides = overridesRaw.mapValues { (site, v) ->
            val sPrefix = "$prefix.perSiteOverrides.$site"
            val bt = try {
                BackoffType.valueOf((v.requireString("backoffType", sPrefix, errors) ?: "EXPONENTIAL").uppercase())
            } catch (e: IllegalArgumentException) {
                errors.add("$sPrefix.backoffType must be one of FIXED, LINEAR, EXPONENTIAL")
                BackoffType.EXPONENTIAL
            }
            RetryStrategy(
                maxRetries = v.requireInt("maxRetries", sPrefix, errors) ?: 0,
                backoffType = bt,
                baseDelayMs = v.requireLong("baseDelayMs", sPrefix, errors) ?: 0,
                jitterRangeMs = v.requireLongRange("jitterRangeMs", sPrefix, errors) ?: 0L..0L
            )
        }

        return RetryConfig(globalMaxRetries, globalBackoffType, globalBaseDelayMs, globalJitterRangeMs, perSiteOverrides)
    }

    private fun parseHumanBehaviorConfig(map: Map<String, Any>): HumanBehaviorConfig {
        return HumanBehaviorConfig(
            initialDelayRange = map.optionalLongRange("initialDelayRange") ?: 1000L..5000L,
            keystrokeDelayRange = map.optionalLongRange("keystrokeDelayRange") ?: 50L..200L,
            scrollPauseRange = map.optionalLongRange("scrollPauseRange") ?: 300L..1500L,
            interActionDelayRange = map.optionalLongRange("interActionDelayRange") ?: 500L..3000L
        )
    }

    private fun parseStealthConfig(map: Map<String, Any>?, errors: MutableList<String>): StealthConfig? {
        if (map == null) return null
        val prefix = "stealth"

        @Suppress("UNCHECKED_CAST")
        val viewportsRaw = map["viewports"] as? List<Map<String, Any>>
        if (viewportsRaw == null || viewportsRaw.isEmpty()) {
            errors.add("$prefix.viewports is required and must not be empty")
            return null
        }
        val viewports = viewportsRaw.mapIndexed { i, v ->
            ViewportSize(
                width = v.requireInt("width", "$prefix.viewports[$i]", errors) ?: 0,
                height = v.requireInt("height", "$prefix.viewports[$i]", errors) ?: 0
            )
        }

        val timezones = map.requireStringList("timezones", prefix, errors) ?: return null
        val languages = map.requireStringList("languages", prefix, errors) ?: return null
        val platforms = map.requireStringList("platforms", prefix, errors) ?: return null
        val webglVendors = map.requireStringList("webglVendors", prefix, errors) ?: return null
        val webglRenderers = map.requireStringList("webglRenderers", prefix, errors) ?: return null

        return StealthConfig(viewports, timezones, languages, platforms, webglVendors, webglRenderers)
    }

    private fun parseCircuitBreakerConfig(map: Map<String, Any>?, errors: MutableList<String>): CircuitBreakerConfig? {
        if (map == null) return null
        val prefix = "circuitBreaker"
        val defaultFailureThreshold = map.requireInt("defaultFailureThreshold", prefix, errors) ?: return null
        val defaultCooldownMs = map.requireLong("defaultCooldownMs", prefix, errors) ?: return null

        if (defaultFailureThreshold <= 0) {
            errors.add("$prefix.defaultFailureThreshold must be positive, got: $defaultFailureThreshold")
            return null
        }
        if (defaultCooldownMs <= 0) {
            errors.add("$prefix.defaultCooldownMs must be positive, got: $defaultCooldownMs")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val overridesRaw = map["domainOverrides"] as? Map<String, Map<String, Any>> ?: emptyMap()
        val domainOverrides = overridesRaw.mapValues { (domain, v) ->
            val dPrefix = "$prefix.domainOverrides.$domain"
            DomainCircuitBreakerConfig(
                failureThreshold = v.requireInt("failureThreshold", dPrefix, errors) ?: 0,
                cooldownMs = v.requireLong("cooldownMs", dPrefix, errors) ?: 0
            )
        }

        return CircuitBreakerConfig(defaultFailureThreshold, defaultCooldownMs, domainOverrides)
    }

    private fun parseWarmupConfig(map: Map<String, Any>?, errors: MutableList<String>): WarmupConfig? {
        if (map == null) return null
        val prefix = "warmup"

        @Suppress("UNCHECKED_CAST")
        val urls = map["urls"] as? List<String>
        if (urls == null || urls.isEmpty()) {
            errors.add("$prefix.urls is required and must not be empty")
            return null
        }

        val minPages = map.requireInt("minPages", prefix, errors) ?: return null
        val maxPages = map.requireInt("maxPages", prefix, errors) ?: return null
        val pageTimeoutMs = map.requireLong("pageTimeoutMs", prefix, errors) ?: return null

        if (minPages < 0) {
            errors.add("$prefix.minPages must be non-negative, got: $minPages")
        }
        if (maxPages < minPages) {
            errors.add("$prefix.maxPages ($maxPages) must be >= minPages ($minPages)")
        }
        if (pageTimeoutMs <= 0) {
            errors.add("$prefix.pageTimeoutMs must be positive, got: $pageTimeoutMs")
        }

        return WarmupConfig(urls, minPages, maxPages, pageTimeoutMs)
    }

    private fun parseWebhookConfig(map: Map<String, Any>?, errors: MutableList<String>): WebhookConfig? {
        if (map == null) return null
        val prefix = "webhook"
        val hmacSecret = map.requireString("hmacSecret", prefix, errors) ?: return null
        val maxRetries = map.requireInt("maxRetries", prefix, errors) ?: return null
        val baseDelayMs = map.requireLong("baseDelayMs", prefix, errors) ?: return null

        if (maxRetries < 0) {
            errors.add("$prefix.maxRetries must be non-negative, got: $maxRetries")
        }
        if (baseDelayMs <= 0) {
            errors.add("$prefix.baseDelayMs must be positive, got: $baseDelayMs")
        }

        return WebhookConfig(hmacSecret, maxRetries, baseDelayMs)
    }

    private fun parseMetricsConfig(map: Map<String, Any>?, errors: MutableList<String>): MetricsConfig? {
        if (map == null) return null
        val prefix = "metrics"
        val reportingIntervalMs = map.requireLong("reportingIntervalMs", prefix, errors) ?: return null
        val slidingWindowMs = map.requireLong("slidingWindowMs", prefix, errors) ?: return null

        if (reportingIntervalMs <= 0) {
            errors.add("$prefix.reportingIntervalMs must be positive, got: $reportingIntervalMs")
        }
        if (slidingWindowMs <= 0) {
            errors.add("$prefix.slidingWindowMs must be positive, got: $slidingWindowMs")
        }

        return MetricsConfig(reportingIntervalMs, slidingWindowMs)
    }

    private fun parseShutdownConfig(map: Map<String, Any>?, errors: MutableList<String>): ShutdownConfig? {
        if (map == null) return null
        val prefix = "shutdown"
        val gracePeriodSeconds = map.requireInt("gracePeriodSeconds", prefix, errors) ?: return null

        if (gracePeriodSeconds <= 0) {
            errors.add("$prefix.gracePeriodSeconds must be positive, got: $gracePeriodSeconds")
        }

        return ShutdownConfig(gracePeriodSeconds)
    }

    private fun parseScraperRegistryConfig(map: Map<String, Any>?, errors: MutableList<String>): ScraperRegistryConfig? {
        if (map == null) return null
        val prefix = "scraperRegistry"
        val scanPackage = map.requireString("scanPackage", prefix, errors) ?: return null

        if (scanPackage.isBlank()) {
            errors.add("$prefix.scanPackage must not be blank")
            return null
        }

        return ScraperRegistryConfig(scanPackage)
    }

    // --- Helper extension functions ---

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.section(key: String, errors: MutableList<String>): Map<String, Any>? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required configuration section: '$key'")
            return null
        }
        return value as? Map<String, Any> ?: run {
            errors.add("Configuration section '$key' must be a map")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.sectionOrEmpty(key: String): Map<String, Any> {
        return (this[key] as? Map<String, Any>) ?: emptyMap()
    }

    private fun Map<String, Any>.requireString(key: String, prefix: String, errors: MutableList<String>): String? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        return value.toString()
    }

    private fun Map<String, Any>.requireInt(key: String, prefix: String, errors: MutableList<String>): Int? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: run {
                errors.add("'$prefix.$key' must be an integer, got: '$value'")
                null
            }
            else -> {
                errors.add("'$prefix.$key' must be an integer, got: '$value'")
                null
            }
        }
    }

    private fun Map<String, Any>.requireLong(key: String, prefix: String, errors: MutableList<String>): Long? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        return when (value) {
            is Long -> value
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: run {
                errors.add("'$prefix.$key' must be a long, got: '$value'")
                null
            }
            else -> {
                errors.add("'$prefix.$key' must be a long, got: '$value'")
                null
            }
        }
    }

    private fun Map<String, Any>.requireDouble(key: String, prefix: String, errors: MutableList<String>): Double? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        return when (value) {
            is Double -> value
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: run {
                errors.add("'$prefix.$key' must be a number, got: '$value'")
                null
            }
            else -> {
                errors.add("'$prefix.$key' must be a number, got: '$value'")
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.requireStringList(key: String, prefix: String, errors: MutableList<String>): List<String>? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        val list = value as? List<*> ?: run {
            errors.add("'$prefix.$key' must be a list")
            return null
        }
        if (list.isEmpty()) {
            errors.add("'$prefix.$key' must not be empty")
            return null
        }
        return list.map { it.toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.requireLongRange(key: String, prefix: String, errors: MutableList<String>): LongRange? {
        val value = this[key]
        if (value == null) {
            errors.add("Missing required config key: '$prefix.$key'")
            return null
        }
        return parseLongRange(value, "$prefix.$key", errors)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.optionalLongRange(key: String): LongRange? {
        val value = this[key] ?: return null
        return parseLongRange(value, key, mutableListOf())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLongRange(value: Any, path: String, errors: MutableList<String>): LongRange? {
        return when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any>
                val min = (map["min"] as? Number)?.toLong()
                val max = (map["max"] as? Number)?.toLong()
                if (min == null || max == null) {
                    errors.add("'$path' must have 'min' and 'max' numeric fields")
                    null
                } else {
                    min..max
                }
            }
            is List<*> -> {
                if (value.size == 2) {
                    val min = (value[0] as? Number)?.toLong()
                    val max = (value[1] as? Number)?.toLong()
                    if (min == null || max == null) {
                        errors.add("'$path' list elements must be numbers")
                        null
                    } else {
                        min..max
                    }
                } else {
                    errors.add("'$path' as a list must have exactly 2 elements [min, max]")
                    null
                }
            }
            else -> {
                errors.add("'$path' must be a map with min/max or a list of [min, max]")
                null
            }
        }
    }
}

class ConfigurationException(message: String) : RuntimeException(message)
