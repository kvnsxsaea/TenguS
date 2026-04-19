# Implementation Plan: TenguS Web Scraper Service

## Overview

Scaffold a Kotlin project from scratch and implement the TenguS web scraper service. Tasks are ordered by dependency: project setup and data models first, then foundational utility components (no external dependencies), then components that depend on them, then the orchestration layer, and finally integration wiring. All code uses Kotlin with Playwright, RabbitMQ, Jackson, Kotest, and SnakeYAML.

## Tasks

- [ ] 1. Project scaffolding and core data models
  - [x] 1.1 Set up Gradle Kotlin project structure
    - Create `build.gradle.kts` with dependencies: Playwright (`com.microsoft.playwright`), RabbitMQ (`com.rabbitmq:amqp-client`), Jackson (`com.fasterxml.jackson.module:jackson-module-kotlin`, `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`), Kotest (`io.kotest:kotest-runner-junit5`, `io.kotest:kotest-property`), SnakeYAML (`org.yaml:snakeyaml`), Kotlin reflection (`org.jetbrains.kotlin:kotlin-reflect`), and SLF4J/Logback for logging
    - Create `settings.gradle.kts` with project name `tengus`
    - Create source directories: `src/main/kotlin/com/tengus/`, `src/main/resources/`, `src/test/kotlin/com/tengus/`
    - Create a `gradle.properties` with Kotlin JVM target
    - _Requirements: 16.1_

  - [x] 1.2 Implement all configuration data models
    - Create `com.tengus.config` package with all config data classes: `AppConfig`, `RabbitMqConfig`, `RateLimitConfig`, `DomainRateLimit`, `ProxyConfig`, `ProxyHealthConfig`, `UserAgentConfig`, `RetryConfig`, `HumanBehaviorConfig`, `StealthConfig`, `CircuitBreakerConfig`, `DomainCircuitBreakerConfig`, `WarmupConfig`, `WebhookConfig`, `MetricsConfig`, `ShutdownConfig`, `ScraperRegistryConfig`
    - Implement `ConfigLoader` class that reads a YAML file via SnakeYAML and maps it to `AppConfig`
    - Implement startup validation: fail with descriptive error if required config keys are missing or invalid
    - _Requirements: 16.1, 16.2, 16.3, 16.4_

  - [x] 1.3 Implement core domain data models
    - Create `com.tengus.model` package with: `ScrapeJob`, `ScrapeResult`, `NormalizedScrapeResult`, `DeadLetterEntry`, `JobFailureNotification`, `ValidationResult`
    - _Requirements: 2.1, 2.2, 2.3, 14.2_

  - [x] 1.4 Implement stealth and proxy data models
    - Create `FingerprintProfile`, `ViewportSize`, `WeightedUserAgent` in `com.tengus.model`
    - Create `ProxyEndpoint`, `BlockingSignal` enum in `com.tengus.model`
    - _Requirements: 6.1, 6.2, 6.3, 9.1, 10.1_

  - [x] 1.5 Implement metrics output data models
    - Create `com.tengus.metrics` package with: `SuccessMetric`, `FailureMetric`, `RollingRateMetric`, `QueueDepthMetric`, `AverageDurationMetric`, `ProxyHealthMetric`
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6_

  - [~] 1.6 Configure Jackson ObjectMapper
    - Create `com.tengus.serialization.JsonMapper` singleton with `JavaTimeModule`, `FAIL_ON_UNKNOWN_PROPERTIES = false`, `WRITE_DATES_AS_TIMESTAMPS = false`, and Kotlin module registered
    - _Requirements: 2.1, 2.2, 2.3, 14.1, 14.3_

  - [~] 1.7 Write unit tests for configuration loading and data model serialization
    - Test YAML config loading with valid and invalid configs
    - Test `ScrapeJob` JSON round-trip serialization/deserialization
    - Test `ScrapeResult` JSON round-trip serialization/deserialization
    - Test all metric model JSON round-trip serialization
    - Test config validation rejects missing required fields with descriptive errors
    - _Requirements: 2.3, 14.3, 16.3, 16.4, 19.7_

  - [~] 1.8 Create sample `application.yml` configuration file
    - Create `src/main/resources/application.yml` with all configuration sections and sensible defaults
    - _Requirements: 16.1, 16.2_

- [~] 2. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 3. User Agent Rotator and Rate Limiter
  - [~] 3.1 Implement `UserAgentRotator`
    - Create `com.tengus.stealth.UserAgentRotator` class
    - Implement weighted-random selection from configured user-agent list
    - Implement consecutive-different guarantee per domain (track last-used per domain)
    - _Requirements: 9.1, 9.2, 9.3_

  - [~] 3.2 Write unit tests for `UserAgentRotator`
    - Test weighted-random selection distribution
    - Test consecutive-different guarantee when multiple agents available
    - Test single-agent edge case
    - _Requirements: 9.1, 9.2, 9.3_

  - [~] 3.3 Implement `RateLimiter`
    - Create `com.tengus.ratelimit.RateLimiter` class
    - Implement sliding window algorithm tracking request timestamps per domain
    - Implement `acquire(domain)` that blocks until permit available
    - Implement `tryAcquire(domain)` for non-blocking check
    - Support per-domain config overrides with global default fallback
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [~] 3.4 Write unit tests for `RateLimiter`
    - Test sliding window correctly limits requests
    - Test per-domain overrides vs global defaults
    - Test blocking behavior when rate exceeded
    - _Requirements: 12.1, 12.2, 12.4, 12.5_

- [ ] 4. Proxy Pool and Proxy Health Monitor
  - [~] 4.1 Implement `ProxyHealthMonitor`
    - Create `com.tengus.proxy.ProxyHealthMonitor` class
    - Track success/failure counts per proxy per domain over sliding window
    - Implement `classifyBlockingSignal` for HTTP 403, CAPTCHA, connection reset detection
    - Implement `isBlocked` check against configurable failure rate threshold
    - Implement `scheduleRecheck` with configurable cooldown
    - Log block and restoration events at WARN level
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5, 27.6_

  - [~] 4.2 Implement `ProxyPool`
    - Create `com.tengus.proxy.ProxyPool` class
    - Implement proxy selection with rotation (consecutive-different per domain)
    - Implement `markUnhealthy` / `restoreProxy` lifecycle
    - Integrate with `ProxyHealthMonitor` for health-aware selection
    - Raise error when all proxies unhealthy
    - Implement periodic re-check of unhealthy proxies
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [~] 4.3 Write unit tests for `ProxyHealthMonitor` and `ProxyPool`
    - Test blocking signal classification
    - Test failure rate threshold triggers block
    - Test proxy rotation consecutive-different guarantee
    - Test all-unhealthy error case
    - Test proxy restoration after cooldown
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 27.1, 27.2, 27.3, 27.4, 27.5_

- [ ] 5. Stealth Manager
  - [~] 5.1 Implement `StealthManager`
    - Create `com.tengus.stealth.StealthManager` class
    - Implement `generateFingerprintProfile` with randomized viewport, timezone, language, platform, WebGL values — all internally consistent with selected user-agent
    - Implement `applyFingerprint` to configure Playwright BrowserContext with the profile
    - Implement `applyBotDetectionPatches` to inject JS patches: `navigator.webdriver=false`, Chrome DevTools detection, `navigator.plugins`, `navigator.mimeTypes`, permissions API
    - Implement `normalizeHeaders` to set Accept, Accept-Language, Accept-Encoding, Sec-CH-UA, Sec-Fetch-* headers consistent with user-agent
    - Implement canvas fingerprint noise injection
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3_

  - [~] 5.2 Write unit tests for `StealthManager`
    - Test fingerprint profile generation produces consistent attribute sets
    - Test header normalization matches user-agent family
    - Test bot detection patch scripts are well-formed
    - _Requirements: 6.5, 8.1, 8.2, 8.3_

- [~] 6. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Human Behavior Engine, Session Manager, and Session Warmup Engine
  - [~] 7.1 Implement `HumanBehaviorEngine`
    - Create `com.tengus.behavior.HumanBehaviorEngine` class
    - Implement `randomDelay` with configurable min/max range (default 1–5s for initial delay)
    - Implement `typeText` with per-keystroke random delay (50–200ms)
    - Implement `scrollPage` with incremental scrolls and random pauses (300–1500ms)
    - Implement `moveMouse` with randomized Bezier curve trajectory
    - Implement `clickElement` that moves mouse to element then clicks
    - Implement `interActionDelay` with configurable range (500–3000ms)
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [~] 7.2 Implement `SessionManager`
    - Create `com.tengus.session.SessionManager` class
    - Implement `createSession` that creates an isolated Playwright BrowserContext per job with empty cookie jar
    - Implement `destroySession` that closes context and discards all cookies/state
    - Ensure no cookie/session data leaks between concurrent jobs
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [~] 7.3 Implement `SessionWarmupEngine`
    - Create `com.tengus.session.SessionWarmupEngine` class
    - Implement `warmup` that navigates to random subset of configured benign URLs (between min and max count)
    - Use `HumanBehaviorEngine` for human-like interactions on warmup pages
    - Accept and retain cookies from warmup pages in the browser context
    - Skip warmup pages that fail to load within configurable timeout, log warning
    - Complete all warmup before stealth config and extraction lifecycle begins
    - _Requirements: 28.1, 28.2, 28.3, 28.4, 28.5, 28.6, 28.7_

  - [~] 7.4 Write unit tests for `HumanBehaviorEngine`, `SessionManager`, and `SessionWarmupEngine`
    - Test delay ranges are within configured bounds
    - Test session isolation (no shared state between jobs)
    - Test warmup page selection respects min/max count
    - Test warmup timeout skip behavior
    - _Requirements: 11.1, 11.2, 11.3, 11.5, 13.4, 28.4, 28.5, 28.6_

- [ ] 8. Circuit Breaker Manager and Retry Policy Resolver
  - [~] 8.1 Implement `CircuitBreakerManager`
    - Create `com.tengus.resilience.CircuitBreakerManager` class with `CircuitState` enum (CLOSED, OPEN, HALF_OPEN)
    - Track consecutive failure count per domain
    - Implement state transitions: CLOSED→OPEN when failures exceed threshold, OPEN→HALF_OPEN after cooldown, HALF_OPEN→CLOSED on success, HALF_OPEN→OPEN on failure
    - Support per-domain config overrides with global default fallback
    - Log all state transitions at WARN level with domain and failure count
    - _Requirements: 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 21.7, 21.8, 21.9_

  - [~] 8.2 Implement `RetryPolicyResolver`
    - Create `com.tengus.resilience.RetryPolicyResolver` class with `RetryStrategy` data class and `BackoffType` enum (FIXED, LINEAR, EXPONENTIAL)
    - Implement `resolve(siteId)` that returns site-specific strategy or global fallback
    - Implement `computeDelay` with fixed/linear/exponential backoff plus jitter
    - Validate all per-site retry config at startup
    - _Requirements: 15.1, 15.2, 15.3, 22.1, 22.2, 22.3, 22.4, 22.5, 22.6_

  - [~] 8.3 Write unit tests for `CircuitBreakerManager` and `RetryPolicyResolver`
    - Test full circuit breaker state machine transitions
    - Test per-domain overrides vs global defaults
    - Test exponential backoff delay computation with jitter
    - Test site-specific vs global retry strategy resolution
    - _Requirements: 21.1, 21.2, 21.4, 21.6, 21.7, 22.2, 22.3, 22.4, 22.5_

- [~] 9. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. Base Scraper and Scraper Registry
  - [~] 10.1 Implement `BaseScraper` abstract class
    - Create `com.tengus.scraper.BaseScraper` abstract class
    - Define abstract `siteId` property and abstract `extract(page, job)` method
    - Implement `execute(job)` template method lifecycle: create browser context → apply stealth (via StealthManager) → session warmup (via SessionWarmupEngine) → invoke `extract` → collect result → close browser context
    - Ensure browser context and resources are closed on both success and failure
    - Log start/completion of each scrape with elapsed time at INFO level
    - Propagate exceptions to caller after cleanup
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 18.2_

  - [~] 10.2 Implement `ScraperRegistry`
    - Create `com.tengus.scraper.ScraperRegistry` class
    - Implement `discover()` using Kotlin reflection to scan configurable package for `BaseScraper` subclasses
    - Implement `register`, `lookup`, `registeredSiteIds`
    - Fail startup with descriptive error on duplicate site identifiers
    - Log count and list of registered site identifiers at INFO level
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5_

  - [~] 10.3 Implement `ScraperFactory`
    - Create `com.tengus.scraper.ScraperFactory` class
    - Delegate all lookups to `ScraperRegistry` (no hardcoded mappings)
    - Implement `createScraper(siteId)` that instantiates the scraper with all injected dependencies (StealthManager, ProxyPool, UserAgentRotator, SessionManager, HumanBehaviorEngine, SessionWarmupEngine)
    - Throw descriptive error for unregistered site identifiers
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 24.6_

  - [~] 10.4 Create an example `SiteScraper` implementation
    - Create `com.tengus.scraper.sites.ExampleSiteScraper` extending `BaseScraper`
    - Implement `siteId` and `extract` method as a reference implementation
    - _Requirements: 4.5, 5.1_

  - [~] 10.5 Write unit tests for `ScraperRegistry` and `ScraperFactory`
    - Test auto-discovery finds scrapers in target package
    - Test duplicate site ID detection fails startup
    - Test factory throws on unregistered site ID
    - Test factory creates scraper with correct dependencies
    - _Requirements: 4.2, 4.3, 24.1, 24.4_

- [ ] 11. Result Normalizer and Webhook Dispatcher
  - [~] 11.1 Implement `ResultNormalizer`
    - Create `com.tengus.result.ResultNormalizer` class
    - Implement `normalize` that transforms `ScrapeResult` into `NormalizedScrapeResult` with source URL, site ID, job ID, extraction timestamp, scraper version, and normalized data payload
    - Implement `validate` that checks normalized result against the common schema
    - Log validation errors with job ID and reject invalid results
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.5, 25.6_

  - [~] 11.2 Implement `WebhookDispatcher`
    - Create `com.tengus.webhook.WebhookDispatcher` class
    - Implement `dispatch` that sends HTTP POST with JSON body to callback URL
    - Implement `dispatchFailure` for failure notifications
    - Implement `computeHmac` for HMAC signature header
    - Implement retry with exponential backoff on 4xx/5xx responses
    - Log delivery failure with job ID, callback URL, and final HTTP status after all retries exhausted
    - _Requirements: 26.2, 26.3, 26.4, 26.5, 26.6_

  - [~] 11.3 Write unit tests for `ResultNormalizer` and `WebhookDispatcher`
    - Test normalization produces correct schema fields
    - Test validation rejects incomplete results
    - Test `NormalizedScrapeResult` JSON round-trip
    - Test HMAC computation consistency
    - Test webhook retry logic on failure responses
    - _Requirements: 25.1, 25.2, 25.3, 25.5, 26.3, 26.4_

- [ ] 12. Metrics Collector
  - [~] 12.1 Implement `MetricsCollector`
    - Create `com.tengus.metrics.MetricsCollector` class
    - Implement `emitSuccess` that writes structured JSON to stdout with job ID, site ID, duration, status, and UTC timestamp
    - Implement `emitFailure` with job ID, site ID, failure reason, retry count, status, and UTC timestamp
    - Implement `emitRollingRates` computing success/failure rates per site over sliding window
    - Implement `emitQueueDepths` for jobs queue and DLQ depth
    - Implement `emitAverageDurations` per site over sliding window
    - Implement `emitProxyHealth` with healthy/blocked counts per domain
    - All metrics include UTC timestamp and metric type identifier
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 20.7, 27.7_

  - [~] 12.2 Write unit tests for `MetricsCollector`
    - Test each metric type emits correct JSON structure
    - Test rolling rate computation
    - Test metric JSON round-trip serialization
    - _Requirements: 19.1, 19.2, 19.3, 19.6, 19.7_

- [~] 13. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Scraper Controller and RabbitMQ integration
  - [~] 14.1 Implement `ScraperController` — message consumption and job dispatch
    - Create `com.tengus.controller.ScraperController` class
    - Implement `start()` that connects to RabbitMQ and begins consuming from jobs queue (FIFO, one at a time)
    - Implement `handleMessage` that deserializes `ScrapeJob` from JSON; reject and log on deserialization failure
    - Implement `dispatchJob` that: checks circuit breaker → acquires rate limiter permit → gets scraper from factory → executes scrape → normalizes result → publishes result
    - Log each job consumption with job ID, site ID, and target URL at INFO level
    - Log warning when extracted data payload is empty
    - _Requirements: 2.2, 2.4, 3.2, 3.4, 4.1, 12.3, 14.4, 18.1_

  - [~] 14.2 Implement `ScraperController` — retry, DLQ, and circuit breaker integration
    - On retryable errors (network timeout, proxy failure, HTTP 429/5xx): re-enqueue with incremented retry count using resolved retry strategy (site-specific or global fallback)
    - On max retries exceeded: route to DLQ preserving original payload, failure reason, retry count, and UTC timestamp
    - On unrecoverable errors: nack message and route to DLQ
    - Integrate circuit breaker: reject jobs for open-state domains (re-enqueue with delay), allow single job in half-open, record success/failure
    - _Requirements: 3.3, 15.1, 15.2, 15.3, 15.4, 20.1, 20.2, 21.3, 21.5, 22.2, 22.3_

  - [~] 14.3 Implement `ScraperController` — result publishing, webhooks, and DLQ management
    - Implement `publishResult` that serializes `NormalizedScrapeResult` and publishes to results queue
    - Integrate `WebhookDispatcher`: dispatch result to callback URL if present; dispatch failure notification on final failure if callback URL present
    - Implement `listDeadLetterMessages`, `replayDeadLetterMessage` (reset retry count to zero), `purgeDeadLetterMessage`
    - Emit success/failure metrics via `MetricsCollector`
    - _Requirements: 3.4, 14.1, 14.5, 20.3, 20.4, 20.5, 20.6, 26.2, 26.6_

  - [~] 14.4 Implement graceful shutdown
    - Register JVM shutdown hook for SIGTERM/SIGINT
    - Stop consuming new messages on signal
    - Wait for in-flight jobs to complete within configurable grace period
    - Force-cancel remaining jobs, nack messages, close browser contexts on timeout
    - Log in-flight count and grace period at INFO on shutdown start; log shutdown-complete at INFO on exit
    - _Requirements: 23.1, 23.2, 23.3, 23.4, 23.5, 23.6_

  - [~] 14.5 Write unit tests for `ScraperController`
    - Test message deserialization success and failure paths
    - Test retry logic with incremented count and backoff
    - Test DLQ routing after max retries
    - Test circuit breaker integration (reject on open, allow on half-open)
    - Test DLQ list/replay/purge operations
    - Test graceful shutdown stops consumption and waits for in-flight
    - _Requirements: 2.2, 2.4, 3.2, 3.3, 15.1, 15.4, 20.3, 20.4, 20.5, 20.6, 21.3, 23.1, 23.2_

- [ ] 15. Application entry point and wiring
  - [~] 15.1 Implement `main` function and dependency wiring
    - Create `com.tengus.Application` with `main` function
    - Load config via `ConfigLoader`
    - Instantiate all components with proper dependency injection order
    - Start `ScraperRegistry.discover()` then `ScraperController.start()`
    - Log health status (RabbitMQ connection, registered scraper count) at configurable interval
    - _Requirements: 16.1, 17.3, 24.1_

  - [~] 15.2 Write integration tests for end-to-end job flow
    - Test full lifecycle: enqueue job → consume → dispatch → extract → normalize → publish result
    - Test retry and DLQ flow end-to-end
    - Test webhook dispatch on success and failure
    - _Requirements: 3.2, 3.4, 14.1, 14.5, 25.6, 26.2_

- [ ] 16. Structured logging setup
  - [~] 16.1 Configure Logback with structured JSON logging
    - Create `src/main/resources/logback.xml` with JSON encoder for structured log output
    - Ensure all components use SLF4J logger with structured fields (job ID, component name, error type)
    - Verify rate limiter logs delays at DEBUG, proxy health changes at WARN, circuit breaker transitions at WARN, errors with structured JSON
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5_

- [~] 17. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Requirements 1 and 17.1/17.2 (Gateway HTTP endpoints) are Node.js scope and excluded from this Kotlin implementation
- Requirement 26.1 (callback URL validation at Gateway) is Node.js scope
- Unit tests validate specific examples, edge cases, and JSON round-trip properties
