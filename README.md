# TenguS
A small study on web scraping and service infrastructure.

## Architecture

```
API Consumer → Node.js Gateway → RabbitMQ → Kotlin Scraper Controller → Playwright Browser
```

Modular Kotlin scraper service with stealth, anti-detection, and polite scraping built in. Each target site gets its own scraper class inheriting from a base class that handles the heavy lifting.

## TO DO

- [ ] Project scaffolding and core data models (Gradle, config loader, domain models, Jackson setup)
- [ ] User Agent Rotator (weighted-random selection, consecutive-different per domain)
- [ ] Rate Limiter (per-domain sliding window)
- [ ] Proxy Pool and Proxy Health Monitor (rotation, blocking detection, cooldown/recovery)
- [ ] Stealth Manager (fingerprint randomization, bot detection patches, header normalization)
- [ ] Human Behavior Engine (delays, typing, scrolling, mouse movement)
- [ ] Session Manager (per-job cookie isolation via Playwright contexts)
- [ ] Session Warmup Engine (benign page navigation before target)
- [ ] Circuit Breaker Manager (per-domain closed/open/half-open state machine)
- [ ] Retry Policy Resolver (per-site and global strategies with backoff + jitter)
- [ ] Base Scraper and Scraper Registry (template method lifecycle, classpath auto-discovery)
- [ ] Scraper Factory (strategy pattern dispatch, no hardcoded mappings)
- [ ] Result Normalizer (common JSON schema, validation)
- [ ] Webhook Dispatcher (HMAC-signed callbacks with retry)
- [ ] Metrics Collector (structured JSON to stdout — rates, durations, queue depths)
- [ ] Scraper Controller + RabbitMQ integration (consumption, dispatch, retry, DLQ, graceful shutdown)
- [ ] Application entry point and dependency wiring
- [ ] Structured logging (Logback JSON, SLF4J)

## Tech Stack

- **Language**: Kotlin (JVM)
- **Browser Automation**: Playwright (`com.microsoft.playwright`)
- **Message Queue**: RabbitMQ (`com.rabbitmq:amqp-client`)
- **Serialization**: Jackson + Kotlin module
- **Configuration**: SnakeYAML
- **Testing**: Kotest + Property-based testing
- **Gateway**: Node.js (separate service)
