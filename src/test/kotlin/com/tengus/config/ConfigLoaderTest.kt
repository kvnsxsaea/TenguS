package com.tengus.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import java.io.File

class ConfigLoaderTest : FunSpec({

    val loader = ConfigLoader()

    fun writeYaml(content: String): String {
        val file = File.createTempFile("test-config", ".yml")
        file.deleteOnExit()
        file.writeText(content)
        return file.absolutePath
    }

    test("loads a valid complete configuration") {
        val path = writeYaml(VALID_CONFIG)
        val config = loader.load(path)

        config.rabbitmq.host shouldBe "localhost"
        config.rabbitmq.port shouldBe 5672
        config.rabbitmq.username shouldBe "guest"
        config.rabbitmq.password shouldBe "guest"
        config.rabbitmq.jobsQueue shouldBe "scrape_jobs"
        config.rabbitmq.resultsQueue shouldBe "scrape_results"
        config.rabbitmq.dlqQueue shouldBe "scrape_dlq"

        config.rateLimiter.defaultMaxRequests shouldBe 10
        config.rateLimiter.defaultWindowMs shouldBe 60000L

        config.proxy.endpoints.size shouldBe 1
        config.proxy.endpoints[0].host shouldBe "proxy1.example.com"
        config.proxy.endpoints[0].port shouldBe 8080
        config.proxy.connectTimeoutMs shouldBe 5000L

        config.userAgent.agents.size shouldBe 1
        config.userAgent.agents[0].weight shouldBe 1.0

        config.retry.globalMaxRetries shouldBe 3
        config.retry.globalBackoffType shouldBe BackoffType.EXPONENTIAL
        config.retry.globalBaseDelayMs shouldBe 1000L
        config.retry.globalJitterRangeMs shouldBe 0L..500L

        config.humanBehavior.initialDelayRange shouldBe 1000L..5000L

        config.stealth.viewports.size shouldBe 1
        config.stealth.timezones shouldBe listOf("America/New_York")

        config.circuitBreaker.defaultFailureThreshold shouldBe 5
        config.circuitBreaker.defaultCooldownMs shouldBe 30000L

        config.warmup.urls shouldBe listOf("https://www.google.com")
        config.warmup.minPages shouldBe 1
        config.warmup.maxPages shouldBe 3

        config.webhook.hmacSecret shouldBe "secret123"
        config.webhook.maxRetries shouldBe 3

        config.metrics.reportingIntervalMs shouldBe 10000L
        config.metrics.slidingWindowMs shouldBe 60000L

        config.shutdown.gracePeriodSeconds shouldBe 30

        config.scraperRegistry.scanPackage shouldBe "com.tengus.scraper.sites"
    }

    test("fails with descriptive error when file not found") {
        val ex = shouldThrow<ConfigurationException> {
            loader.load("/nonexistent/path.yml")
        }
        ex.message shouldContain "not found"
    }

    test("fails with descriptive error when required section is missing") {
        val path = writeYaml("""
            rabbitmq:
              host: localhost
              port: 5672
              username: guest
              password: guest
              jobsQueue: jobs
              resultsQueue: results
              dlqQueue: dlq
        """.trimIndent())

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "Missing required configuration section"
        ex.message shouldContain "rateLimiter"
    }

    test("fails with descriptive error when required key is missing within a section") {
        val config = VALID_CONFIG.replace("host: localhost", "# host removed")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "rabbitmq.host"
    }

    test("fails when rabbitmq port is out of range") {
        val config = VALID_CONFIG.replace("port: 5672", "port: 99999")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "port"
        ex.message shouldContain "between 1 and 65535"
    }

    test("fails when rateLimiter defaultMaxRequests is not positive") {
        val config = VALID_CONFIG.replace("defaultMaxRequests: 10", "defaultMaxRequests: 0")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "defaultMaxRequests"
        ex.message shouldContain "positive"
    }

    test("fails when proxy endpoints list is empty") {
        val config = VALID_CONFIG.replace(
            """endpoints:
    - host: proxy1.example.com
      port: 8080""",
            "endpoints: []"
        )
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "endpoints"
        ex.message shouldContain "not be empty"
    }

    test("fails when backoff type is invalid") {
        val config = VALID_CONFIG.replace("globalBackoffType: EXPONENTIAL", "globalBackoffType: INVALID")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "globalBackoffType"
        ex.message shouldContain "FIXED, LINEAR, EXPONENTIAL"
    }

    test("fails when warmup maxPages is less than minPages") {
        val config = VALID_CONFIG
            .replace("minPages: 1", "minPages: 5")
            .replace("maxPages: 3", "maxPages: 2")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "maxPages"
        ex.message shouldContain "minPages"
    }

    test("fails when config file is empty") {
        val path = writeYaml("")

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "empty"
    }

    test("loads humanBehavior defaults when section is absent") {
        // humanBehavior is optional with defaults
        val config = VALID_CONFIG.lines().filterNot { line ->
            line.trimStart().startsWith("initialDelayRange:") ||
            line.trimStart().startsWith("keystrokeDelayRange:") ||
            line.trimStart().startsWith("scrollPauseRange:") ||
            line.trimStart().startsWith("interActionDelayRange:") ||
            line.trimStart().startsWith("min:") && false // keep min/max for other sections
        }
        // Just remove the humanBehavior section entirely
        val configNoHB = VALID_CONFIG.replace(
            """humanBehavior:
  initialDelayRange:
    min: 1000
    max: 5000
  keystrokeDelayRange:
    min: 50
    max: 200
  scrollPauseRange:
    min: 300
    max: 1500
  interActionDelayRange:
    min: 500
    max: 3000""",
            "humanBehavior:"
        )
        val path = writeYaml(configNoHB)
        val cfg = loader.load(path)

        cfg.humanBehavior.initialDelayRange shouldBe 1000L..5000L
        cfg.humanBehavior.keystrokeDelayRange shouldBe 50L..200L
        cfg.humanBehavior.scrollPauseRange shouldBe 300L..1500L
        cfg.humanBehavior.interActionDelayRange shouldBe 500L..3000L
    }

    test("parses domain overrides for rateLimiter") {
        val config = VALID_CONFIG.replaceFirst(
            "  domainOverrides: {}\n\nproxy:",
            """  domainOverrides:
    example.com:
      maxRequests: 5
      windowMs: 30000

proxy:"""
        )
        val path = writeYaml(config)
        val cfg = loader.load(path)

        cfg.rateLimiter.domainOverrides["example.com"]!!.maxRequests shouldBe 5
        cfg.rateLimiter.domainOverrides["example.com"]!!.windowMs shouldBe 30000L
    }

    test("parses circuit breaker domain overrides") {
        val config = VALID_CONFIG.replaceFirst(
            "  domainOverrides: {}\n\nwarmup:",
            """  domainOverrides:
    fragile.com:
      failureThreshold: 2
      cooldownMs: 60000

warmup:"""
        )
        val path = writeYaml(config)
        val cfg = loader.load(path)

        cfg.circuitBreaker.domainOverrides["fragile.com"]!!.failureThreshold shouldBe 2
        cfg.circuitBreaker.domainOverrides["fragile.com"]!!.cooldownMs shouldBe 60000L
    }

    test("fails when scraperRegistry scanPackage is blank") {
        val config = VALID_CONFIG.replace("scanPackage: com.tengus.scraper.sites", "scanPackage: \"\"")
        val path = writeYaml(config)

        val ex = shouldThrow<ConfigurationException> {
            loader.load(path)
        }
        ex.message shouldContain "scanPackage"
        ex.message shouldContain "blank"
    }
})

private val VALID_CONFIG = """
rabbitmq:
  host: localhost
  port: 5672
  username: guest
  password: guest
  jobsQueue: scrape_jobs
  resultsQueue: scrape_results
  dlqQueue: scrape_dlq

rateLimiter:
  defaultMaxRequests: 10
  defaultWindowMs: 60000
  domainOverrides: {}

proxy:
  endpoints:
    - host: proxy1.example.com
      port: 8080
  connectTimeoutMs: 5000
  healthCheckIntervalMs: 30000

userAgent:
  agents:
    - userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
      weight: 1.0
      browserFamily: Chrome
      browserVersion: "120.0"

retry:
  globalMaxRetries: 3
  globalBackoffType: EXPONENTIAL
  globalBaseDelayMs: 1000
  globalJitterRangeMs:
    min: 0
    max: 500

humanBehavior:
  initialDelayRange:
    min: 1000
    max: 5000
  keystrokeDelayRange:
    min: 50
    max: 200
  scrollPauseRange:
    min: 300
    max: 1500
  interActionDelayRange:
    min: 500
    max: 3000

stealth:
  viewports:
    - width: 1920
      height: 1080
  timezones:
    - America/New_York
  languages:
    - en-US
  platforms:
    - Win32
  webglVendors:
    - "Google Inc. (NVIDIA)"
  webglRenderers:
    - "ANGLE (NVIDIA, NVIDIA GeForce GTX 1080)"

circuitBreaker:
  defaultFailureThreshold: 5
  defaultCooldownMs: 30000
  domainOverrides: {}

warmup:
  urls:
    - https://www.google.com
  minPages: 1
  maxPages: 3
  pageTimeoutMs: 10000

webhook:
  hmacSecret: secret123
  maxRetries: 3
  baseDelayMs: 1000

metrics:
  reportingIntervalMs: 10000
  slidingWindowMs: 60000

shutdown:
  gracePeriodSeconds: 30

scraperRegistry:
  scanPackage: com.tengus.scraper.sites
""".trimIndent()
