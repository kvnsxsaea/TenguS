# Requirements Document

## Introduction

TenguS is a modular, scalable web scraper service built in Kotlin. The system uses a Node.js gateway for scrape job submission, RabbitMQ for message-based decoupling, and a Kotlin scraper controller that consumes jobs and dispatches site-specific scrapers. Each website scraper inherits from a base scraper class that provides stealth, anti-detection, and polite scraping capabilities out of the box. Headless Playwright handles browser automation. No database is used at this stage.

## Glossary

- **Gateway**: The Node.js HTTP service that accepts incoming scrape job submissions and publishes them to the Message_Queue.
- **Message_Queue**: The RabbitMQ instance that decouples the Gateway from the Scraper_Controller via asynchronous message passing.
- **Scraper_Controller**: The Kotlin service that consumes scrape jobs from the Message_Queue and dispatches them to the appropriate Site_Scraper using the Scraper_Factory.
- **Scraper_Factory**: The factory component within the Scraper_Controller that maps a target site identifier to the correct Site_Scraper class and instantiates it.
- **Base_Scraper**: The abstract Kotlin class that encapsulates all shared scraping logic including stealth features, proxy rotation, user-agent rotation, rate limiting, cookie management, and human-like interaction patterns.
- **Site_Scraper**: A concrete Kotlin class that extends Base_Scraper to implement extraction logic for a specific website.
- **Stealth_Manager**: The component responsible for browser fingerprint randomization, stealth plugin patches, and request header normalization. Operates independently from Base_Scraper and is injected as a dependency.
- **Proxy_Pool**: The component that manages a collection of proxy endpoints and provides rotation logic for outbound browser connections. Operates as a standalone service injected into Base_Scraper.
- **User_Agent_Rotator**: The component that manages a collection of user-agent strings and selects one per browser session. Operates independently from Proxy_Pool.
- **Rate_Limiter**: The component that enforces per-domain request rate limits to ensure polite scraping behavior. Operates as a standalone, shared service across all scraper instances.
- **Session_Manager**: The component that manages cookie and session state, persisting cookies within a single scrape job and isolating state between separate jobs.
- **Human_Behavior_Engine**: The component that provides human-like interaction utilities (delays, mouse movement, typing, scrolling). Injected into Base_Scraper as a dependency.
- **Scrape_Job**: A message consumed from the Message_Queue containing the target URL, site identifier, and any job-specific parameters.
- **Scrape_Result**: The structured output produced by a Site_Scraper after successfully extracting data from a target page.
- **Retry_Policy**: The configuration that defines how many times and with what backoff strategy a failed Scrape_Job is retried before being sent to the dead-letter queue.
- **Health_Check**: An endpoint or mechanism that reports the operational status of system components.
- **Metrics_Collector**: The component that aggregates and emits structured operational metrics (success/failure rates, scrape durations, queue depth) to stdout in a structured format.
- **Dead_Letter_Queue**: A dedicated RabbitMQ queue that receives Scrape_Jobs which have exhausted all retry attempts, enabling inspection, monitoring, and replay of failed jobs.
- **Circuit_Breaker**: The component that monitors per-domain failure rates and automatically suspends scraping for a target domain when failures exceed a configurable threshold, preventing resource waste on consistently failing targets.
- **Scraper_Registry**: The component that provides auto-discovery and registration of Site_Scraper implementations, enabling new scrapers to be added without modifying existing dispatch or factory code.
- **Result_Normalizer**: The component that transforms raw Site_Scraper output into a standardized JSON schema, ensuring downstream consumers receive a consistent data contract regardless of the originating scraper.
- **Webhook_Dispatcher**: The component that delivers Scrape_Results to caller-specified HTTP webhook URLs upon job completion or failure.
- **Proxy_Health_Monitor**: The component that proactively tracks proxy blocking patterns and detection signals across target domains, removing compromised proxies from rotation before they cause job failures.
- **Session_Warmup_Engine**: The component that navigates a sequence of benign pages before visiting the target URL to establish a realistic browsing history and cookie profile within the browser context.

## Requirements

---

### Requirement 1: Scrape Job Submission

**User Story:** As an API consumer, I want to submit scrape jobs via an HTTP endpoint, so that I can trigger scraping without direct access to the internal queue.

#### Acceptance Criteria

1. WHEN a valid Scrape_Job payload is received, THE Gateway SHALL publish the Scrape_Job to the Message_Queue and return an HTTP 202 Accepted response with the assigned job identifier.
2. WHEN a Scrape_Job payload is missing required fields (target URL or site identifier), THE Gateway SHALL return an HTTP 400 Bad Request response with a descriptive error message listing the missing fields.
3. IF the Message_Queue is unreachable, THEN THE Gateway SHALL return an HTTP 503 Service Unavailable response and log the connection failure.
4. THE Gateway SHALL validate that the target URL in the Scrape_Job payload is a well-formed HTTP or HTTPS URL.

---

### Requirement 2: Scrape Job Serialization

**User Story:** As a developer, I want scrape jobs serialized and deserialized consistently, so that the Gateway and Scraper_Controller share a reliable contract.

#### Acceptance Criteria

1. THE Gateway SHALL serialize each Scrape_Job as a JSON message before publishing to the Message_Queue.
2. THE Scraper_Controller SHALL deserialize each message from the Message_Queue into a Scrape_Job object.
3. FOR ALL valid Scrape_Job objects, serializing to JSON then deserializing back SHALL produce an equivalent Scrape_Job object (round-trip property).
4. IF a message cannot be deserialized into a valid Scrape_Job, THEN THE Scraper_Controller SHALL reject the message and log the deserialization error with the raw message content.

---

### Requirement 3: Message Queue Integration

**User Story:** As a system operator, I want scrape jobs decoupled from scraping execution via a message queue, so that the system can handle bursts of submissions without overwhelming scrapers.

#### Acceptance Criteria

1. THE Gateway SHALL publish each accepted Scrape_Job as a message to a dedicated RabbitMQ scrape jobs queue.
2. THE Scraper_Controller SHALL consume Scrape_Job messages from the Message_Queue one at a time in FIFO order.
3. IF a Site_Scraper throws an unrecoverable error during execution, THEN THE Scraper_Controller SHALL negatively acknowledge the message and route it to a dead-letter queue.
4. WHEN a Scrape_Job is successfully processed, THE Scraper_Controller SHALL acknowledge the message to remove it from the queue.

---

### Requirement 4: Scraper Dispatch via Factory Pattern

**User Story:** As a developer, I want new site scrapers to be discoverable and instantiated automatically, so that adding support for a new website requires only creating a new Site_Scraper class.

#### Acceptance Criteria

1. WHEN a Scrape_Job is consumed, THE Scraper_Controller SHALL pass the site identifier to the Scraper_Factory to obtain the appropriate Site_Scraper instance.
2. THE Scraper_Factory SHALL maintain a registry mapping site identifiers to Site_Scraper classes.
3. IF the Scraper_Factory receives an unregistered site identifier, THEN THE Scraper_Factory SHALL throw a descriptive error indicating the unsupported site identifier.
4. WHEN a new Site_Scraper class is registered with the Scraper_Factory, THE Scraper_Factory SHALL make the new Site_Scraper available for dispatch without modification to existing Scraper_Factory code.
5. THE Scraper_Factory SHALL use the Strategy pattern so that each Site_Scraper encapsulates its own extraction algorithm independently.

---

### Requirement 5: Base Scraper Lifecycle

**User Story:** As a developer, I want all shared scraping logic centralized in a base class with a clear lifecycle, so that site-specific scrapers only need to implement extraction logic.

#### Acceptance Criteria

1. THE Base_Scraper SHALL define an abstract extraction method that each Site_Scraper must implement.
2. THE Base_Scraper SHALL execute a lifecycle of: initialize browser context → apply stealth → invoke extraction → collect result → close browser context.
3. THE Base_Scraper SHALL close the Playwright browser context and release all resources after the extraction method completes or fails.
4. WHEN a Site_Scraper completes extraction, THE Base_Scraper SHALL return the Scrape_Result to the Scraper_Controller.
5. IF the extraction method throws an exception, THEN THE Base_Scraper SHALL log the error, close the browser context, and propagate the exception to the Scraper_Controller.

---

### Requirement 6: Stealth Manager — Browser Fingerprint Randomization

**User Story:** As a system operator, I want each browser session to present a unique fingerprint, so that target sites cannot correlate multiple scrape requests to the same client.

#### Acceptance Criteria

1. WHEN a new browser context is created, THE Stealth_Manager SHALL assign a randomized viewport size from a predefined set of common screen resolutions.
2. WHEN a new browser context is created, THE Stealth_Manager SHALL assign a randomized timezone, language, and platform string consistent with the selected user-agent.
3. WHEN a new browser context is created, THE Stealth_Manager SHALL inject scripts to override WebGL renderer and vendor strings with randomized values.
4. WHEN a new browser context is created, THE Stealth_Manager SHALL inject scripts to override canvas fingerprinting by adding imperceptible noise to canvas readback operations.
5. WHEN generating a fingerprint profile, THE Stealth_Manager SHALL ensure that all fingerprint attributes (viewport, timezone, language, platform, user-agent) are internally consistent with each other.

---

### Requirement 7: Stealth Manager — Bot Detection Patches

**User Story:** As a system operator, I want common bot-detection vectors patched automatically, so that scrapers are not flagged by standard anti-bot systems.

#### Acceptance Criteria

1. WHEN a new browser context is created, THE Stealth_Manager SHALL patch the navigator.webdriver property to return false.
2. WHEN a new browser context is created, THE Stealth_Manager SHALL patch Chrome DevTools Protocol detection vectors to prevent leak-based bot identification.
3. WHEN a new browser context is created, THE Stealth_Manager SHALL patch the navigator.plugins and navigator.mimeTypes arrays to match a standard Chrome browser profile.
4. WHEN a new browser context is created, THE Stealth_Manager SHALL patch the permissions API to return results consistent with a real user browser session.

---

### Requirement 8: Stealth Manager — Request Header Normalization

**User Story:** As a system operator, I want outgoing request headers to match the selected user-agent profile, so that header inconsistencies do not trigger bot detection.

#### Acceptance Criteria

1. WHEN a user-agent is selected, THE Stealth_Manager SHALL set the Accept, Accept-Language, Accept-Encoding, and Sec-CH-UA headers to values consistent with the selected user-agent's browser family and version.
2. THE Stealth_Manager SHALL set the Sec-Fetch-Site, Sec-Fetch-Mode, Sec-Fetch-User, and Sec-Fetch-Dest headers to values matching a standard navigation request.
3. THE Stealth_Manager SHALL omit or normalize any headers that are not present in a standard browser request for the selected user-agent profile.

---

### Requirement 9: User-Agent Rotation

**User Story:** As a system operator, I want user-agents rotated across browser sessions, so that no single browser identity is overused.

#### Acceptance Criteria

1. WHEN a new browser context is created, THE User_Agent_Rotator SHALL select a user-agent string using a weighted-random strategy favoring common browser versions.
2. THE User_Agent_Rotator SHALL maintain a configurable list of user-agent strings loaded from a configuration file.
3. THE User_Agent_Rotator SHALL ensure that consecutive selections for the same domain produce different user-agent strings when more than one user-agent is available.

---

### Requirement 10: Proxy Rotation

**User Story:** As a system operator, I want proxy endpoints rotated across requests, so that no single IP address is overused on a target domain.

#### Acceptance Criteria

1. WHEN a new browser context is created, THE Proxy_Pool SHALL select a proxy endpoint for the outbound connection.
2. THE Proxy_Pool SHALL rotate proxy endpoints such that consecutive requests to the same domain use different proxies when more than one proxy is available.
3. IF a proxy endpoint fails to connect within a configurable timeout, THEN THE Proxy_Pool SHALL mark the proxy as unhealthy and select the next available proxy.
4. THE Proxy_Pool SHALL periodically re-check unhealthy proxies and restore them to the rotation pool when they become reachable.
5. IF all proxy endpoints are unhealthy, THEN THE Proxy_Pool SHALL raise an error and THE Scraper_Controller SHALL log the failure and reject the Scrape_Job.

---

### Requirement 11: Human-Like Interaction Patterns

**User Story:** As a system operator, I want the scraper to mimic human browsing behavior, so that behavioral analysis systems do not flag automated activity.

#### Acceptance Criteria

1. WHEN navigating to a page, THE Human_Behavior_Engine SHALL introduce a random delay between 1 and 5 seconds before performing the first interaction.
2. WHEN typing into an input field, THE Human_Behavior_Engine SHALL type each character with a random inter-keystroke delay between 50 and 200 milliseconds.
3. WHEN scrolling a page, THE Human_Behavior_Engine SHALL scroll in increments with random pauses between 300 and 1500 milliseconds to simulate human reading behavior.
4. WHEN clicking an element, THE Human_Behavior_Engine SHALL move the mouse cursor to the element coordinates with a randomized Bezier curve trajectory before clicking.
5. WHEN performing sequential page interactions, THE Human_Behavior_Engine SHALL introduce random delays between 500 and 3000 milliseconds between actions.

---

### Requirement 12: Per-Domain Rate Limiting

**User Story:** As a system operator, I want request rates limited per domain, so that the scraper behaves politely and avoids triggering IP bans.

#### Acceptance Criteria

1. THE Rate_Limiter SHALL enforce a configurable maximum number of requests per time window for each target domain.
2. WHILE the rate limit for a domain is exceeded, THE Rate_Limiter SHALL delay subsequent requests to that domain until the current time window expires.
3. WHEN a Scrape_Job targets a rate-limited domain, THE Scraper_Controller SHALL wait for the Rate_Limiter to grant permission before dispatching the Site_Scraper.
4. THE Rate_Limiter SHALL use a sliding window algorithm to track request counts per domain.
5. THE Rate_Limiter SHALL support per-domain configuration overrides, falling back to a global default when no domain-specific configuration exists.

---

### Requirement 13: Cookie and Session Management

**User Story:** As a system operator, I want cookies persisted within a scrape job but isolated between jobs, so that session state is maintained during multi-page scraping without cross-contamination.

#### Acceptance Criteria

1. WHEN a browser context is created for a Scrape_Job, THE Session_Manager SHALL initialize an empty cookie jar dedicated to that job.
2. WHILE a Scrape_Job is executing, THE Session_Manager SHALL persist all cookies set by the target site within the browser context for the duration of the job.
3. WHEN a Scrape_Job completes or fails, THE Session_Manager SHALL discard all cookies and session state associated with that job.
4. THE Session_Manager SHALL ensure that no cookie or session data leaks between concurrent Scrape_Jobs by using isolated Playwright browser contexts per job.

---

### Requirement 14: Scrape Result Handling

**User Story:** As an API consumer, I want scrape results returned in a structured format, so that downstream systems can process extracted data consistently.

#### Acceptance Criteria

1. WHEN a Site_Scraper completes extraction, THE Scraper_Controller SHALL serialize the Scrape_Result as JSON.
2. THE Scrape_Result SHALL contain the source URL, the site identifier, the job identifier, a timestamp of extraction, and the extracted data payload.
3. FOR ALL valid Scrape_Result objects, serializing to JSON then deserializing back SHALL produce an equivalent Scrape_Result object (round-trip property).
4. IF the extracted data payload is empty, THEN THE Scraper_Controller SHALL log a warning indicating that no data was extracted for the given Scrape_Job.
5. WHEN a Scrape_Result is produced, THE Scraper_Controller SHALL publish the result to a dedicated results queue on the Message_Queue.

---

### Requirement 15: Retry Policy for Failed Jobs

**User Story:** As a system operator, I want failed scrape jobs retried with exponential backoff, so that transient failures are recovered automatically without manual intervention.

#### Acceptance Criteria

1. IF a Site_Scraper fails with a retryable error (network timeout, proxy failure, HTTP 429 or 5xx response), THEN THE Scraper_Controller SHALL re-enqueue the Scrape_Job with an incremented retry count.
2. THE Retry_Policy SHALL enforce a configurable maximum retry count per Scrape_Job.
3. THE Retry_Policy SHALL apply exponential backoff with jitter between retry attempts.
4. IF a Scrape_Job exceeds the maximum retry count, THEN THE Scraper_Controller SHALL route the message to the dead-letter queue and log the final failure.

---

### Requirement 16: Configuration Management

**User Story:** As a system operator, I want all tunable parameters externalized to configuration, so that I can adjust scraper behavior without code changes.

#### Acceptance Criteria

1. THE Scraper_Controller SHALL load configuration from a YAML or JSON configuration file at startup.
2. THE configuration file SHALL support settings for: Rate_Limiter windows and limits, Proxy_Pool endpoints, User_Agent_Rotator agent list, Retry_Policy max retries and backoff parameters, and Human_Behavior_Engine delay ranges.
3. IF a required configuration value is missing, THEN THE Scraper_Controller SHALL fail to start and log a descriptive error indicating the missing configuration key.
4. THE Scraper_Controller SHALL validate all configuration values at startup and reject invalid values with descriptive error messages.

---

### Requirement 17: Health Check Endpoint

**User Story:** As a system operator, I want a health check mechanism, so that I can monitor whether the scraper service and its dependencies are operational.

#### Acceptance Criteria

1. THE Gateway SHALL expose an HTTP GET /health endpoint that returns HTTP 200 when the Gateway is operational and the Message_Queue connection is active.
2. IF the Message_Queue connection is down, THEN THE Gateway /health endpoint SHALL return HTTP 503 with a message indicating the queue is unreachable.
3. THE Scraper_Controller SHALL log its health status (connected to Message_Queue, active scraper count) at a configurable interval.

---

### Requirement 18: Logging and Observability

**User Story:** As a system operator, I want structured logging across all components, so that I can diagnose issues and monitor scraper health.

#### Acceptance Criteria

1. THE Scraper_Controller SHALL log each Scrape_Job consumption with the job identifier, site identifier, and target URL at INFO level.
2. THE Base_Scraper SHALL log the start and completion of each scrape execution with elapsed time at INFO level.
3. IF any component encounters an error, THEN that component SHALL log the error with a structured JSON message containing the job identifier, component name, error type, and error details.
4. THE Rate_Limiter SHALL log each time a request is delayed due to rate limiting, including the domain and wait duration at DEBUG level.
5. THE Proxy_Pool SHALL log proxy health state changes (healthy to unhealthy and vice versa) at WARN level.


---

### Requirement 19: Metrics Collection

**User Story:** As a system operator, I want structured operational metrics emitted to stdout, so that I can monitor scraper performance, detect degradation, and feed metrics into external observability pipelines.

#### Acceptance Criteria

1. WHEN a Scrape_Job completes successfully, THE Metrics_Collector SHALL emit a structured JSON metric to stdout containing the job identifier, site identifier, scrape duration in milliseconds, and a status of "success".
2. WHEN a Scrape_Job fails after all retries are exhausted, THE Metrics_Collector SHALL emit a structured JSON metric to stdout containing the job identifier, site identifier, failure reason, retry count, and a status of "failure".
3. THE Metrics_Collector SHALL compute and emit a rolling success rate and failure rate per site identifier at a configurable reporting interval.
4. THE Metrics_Collector SHALL emit the current Message_Queue depth for the scrape jobs queue at a configurable reporting interval.
5. THE Metrics_Collector SHALL compute and emit the average scrape duration per site identifier over a configurable sliding window.
6. FOR ALL emitted metrics, THE Metrics_Collector SHALL include a UTC timestamp and a metric type identifier in each structured JSON record.
7. FOR ALL emitted metric JSON objects, serializing to JSON then deserializing back SHALL produce an equivalent metric object (round-trip property).

---

### Requirement 20: Dead Letter Queue Handling

**User Story:** As a system operator, I want to monitor, inspect, and replay jobs in the dead-letter queue, so that poison messages do not block the pipeline and failed jobs can be investigated and reprocessed.

#### Acceptance Criteria

1. THE Dead_Letter_Queue SHALL be a dedicated RabbitMQ queue that receives Scrape_Jobs routed by the Scraper_Controller after the Retry_Policy maximum retry count is exceeded.
2. THE Scraper_Controller SHALL preserve the original Scrape_Job payload, the final failure reason, the retry count, and a UTC timestamp when routing a message to the Dead_Letter_Queue.
3. THE Scraper_Controller SHALL expose a mechanism to list all messages currently in the Dead_Letter_Queue with their job identifiers, site identifiers, failure reasons, and enqueue timestamps.
4. THE Scraper_Controller SHALL expose a mechanism to replay a specific message from the Dead_Letter_Queue back to the main scrape jobs queue by job identifier.
5. THE Scraper_Controller SHALL expose a mechanism to purge a specific message from the Dead_Letter_Queue by job identifier.
6. WHEN a message is replayed from the Dead_Letter_Queue, THE Scraper_Controller SHALL reset the retry count to zero on the re-enqueued Scrape_Job.
7. THE Metrics_Collector SHALL emit the current Dead_Letter_Queue depth at the same configurable reporting interval used for the scrape jobs queue depth.

---

### Requirement 21: Circuit Breaker per Target Site

**User Story:** As a system operator, I want the system to automatically back off from target sites that are consistently blocking or timing out, so that proxies and resources are not wasted on unreachable targets.

#### Acceptance Criteria

1. THE Circuit_Breaker SHALL track the count of consecutive failures per target domain.
2. WHEN the consecutive failure count for a domain exceeds a configurable threshold, THE Circuit_Breaker SHALL transition to an open state for that domain.
3. WHILE the Circuit_Breaker is in an open state for a domain, THE Scraper_Controller SHALL reject Scrape_Jobs targeting that domain and re-enqueue them with a configurable delay.
4. WHEN the Circuit_Breaker is in an open state and a configurable cooldown period has elapsed, THE Circuit_Breaker SHALL transition to a half-open state for that domain.
5. WHILE the Circuit_Breaker is in a half-open state for a domain, THE Scraper_Controller SHALL allow a single Scrape_Job through to test connectivity.
6. WHEN a Scrape_Job succeeds while the Circuit_Breaker is in a half-open state, THE Circuit_Breaker SHALL transition to a closed state and reset the failure count for that domain.
7. IF a Scrape_Job fails while the Circuit_Breaker is in a half-open state, THEN THE Circuit_Breaker SHALL transition back to an open state and restart the cooldown period.
8. THE Circuit_Breaker SHALL log each state transition (closed to open, open to half-open, half-open to closed, half-open to open) at WARN level with the domain name and failure count.
9. THE configuration file SHALL support per-domain overrides for Circuit_Breaker failure threshold and cooldown period, falling back to global defaults.

---

### Requirement 22: Configurable Retry Strategies per Scraper

**User Story:** As a developer, I want to configure retry strategies on a per-scraper basis, so that different target sites can have tailored retry behavior beyond the global retry policy.

#### Acceptance Criteria

1. THE configuration file SHALL support a per-site-identifier retry strategy section specifying maximum retry count and backoff parameters.
2. WHEN a Scrape_Job fails with a retryable error, THE Scraper_Controller SHALL apply the site-specific retry strategy if one is configured for the Scrape_Job site identifier.
3. IF no site-specific retry strategy is configured for a site identifier, THEN THE Scraper_Controller SHALL fall back to the global Retry_Policy.
4. THE per-site retry strategy SHALL support configurable backoff type (fixed, linear, or exponential) and a configurable base delay in milliseconds.
5. THE per-site retry strategy SHALL support a configurable jitter range to add randomness to backoff delays.
6. THE Scraper_Controller SHALL validate all per-site retry strategy configuration values at startup and reject invalid values with descriptive error messages.

---

### Requirement 23: Graceful Shutdown

**User Story:** As a system operator, I want in-flight scrape jobs to complete before the process exits, so that no work is lost and browser resources are properly released during deployments or restarts.

#### Acceptance Criteria

1. WHEN the Scraper_Controller receives a SIGTERM or SIGINT signal, THE Scraper_Controller SHALL stop consuming new messages from the Message_Queue.
2. WHEN the Scraper_Controller receives a SIGTERM or SIGINT signal, THE Scraper_Controller SHALL wait for all in-flight Scrape_Jobs to complete before shutting down.
3. THE Scraper_Controller SHALL enforce a configurable maximum shutdown grace period in seconds.
4. IF in-flight Scrape_Jobs do not complete within the maximum shutdown grace period, THEN THE Scraper_Controller SHALL forcefully cancel remaining jobs, negatively acknowledge their messages, close all browser contexts, and exit.
5. WHEN the Scraper_Controller begins graceful shutdown, THE Scraper_Controller SHALL log the number of in-flight jobs and the configured grace period at INFO level.
6. WHEN the Scraper_Controller completes graceful shutdown, THE Scraper_Controller SHALL log a shutdown-complete message at INFO level before exiting.

---

### Requirement 24: Scraper Registration and Discovery

**User Story:** As a developer, I want new site scrapers to be automatically discovered and registered at startup, so that adding a new scraper requires only creating a new class without modifying controller or factory code.

#### Acceptance Criteria

1. THE Scraper_Registry SHALL scan a configurable classpath package at startup and discover all classes that extend Base_Scraper.
2. WHEN a class extending Base_Scraper is discovered, THE Scraper_Registry SHALL register the class with the Scraper_Factory using the site identifier declared by the class.
3. THE Base_Scraper SHALL define an abstract property or method that each Site_Scraper must implement to declare its site identifier.
4. IF two Site_Scraper classes declare the same site identifier, THEN THE Scraper_Registry SHALL fail startup and log a descriptive error indicating the duplicate site identifier and the conflicting class names.
5. WHEN the Scraper_Registry completes discovery, THE Scraper_Registry SHALL log the count and list of registered site identifiers at INFO level.
6. THE Scraper_Factory SHALL delegate all registration lookups to the Scraper_Registry, maintaining no hardcoded site identifier mappings.

---

### Requirement 25: Result Normalization

**User Story:** As a downstream consumer, I want all scrape results to conform to a common JSON schema regardless of which scraper produced them, so that result processing logic does not need per-site handling.

#### Acceptance Criteria

1. WHEN a Site_Scraper produces a Scrape_Result, THE Result_Normalizer SHALL transform the extracted data payload into a standardized JSON structure defined by a common result schema.
2. THE common result schema SHALL include fields for: source URL, site identifier, job identifier, extraction timestamp, scraper version, and a normalized data payload object.
3. THE Result_Normalizer SHALL validate each normalized Scrape_Result against the common result schema before publishing.
4. IF a Scrape_Result fails schema validation, THEN THE Result_Normalizer SHALL log the validation errors with the job identifier and reject the result.
5. FOR ALL valid normalized Scrape_Result objects, serializing to JSON then deserializing back SHALL produce an equivalent object (round-trip property).
6. THE Result_Normalizer SHALL be invoked by the Scraper_Controller after extraction and before publishing the result to the results queue on the Message_Queue.

---

### Requirement 26: Result Callbacks and Webhooks

**User Story:** As an API consumer, I want scrape results pushed to a webhook URL that I specify at submission time, so that I do not need to poll for results.

#### Acceptance Criteria

1. WHEN a Scrape_Job payload includes a callback URL field, THE Gateway SHALL validate that the callback URL is a well-formed HTTPS URL and include it in the published message.
2. WHEN a Scrape_Result is produced for a Scrape_Job that includes a callback URL, THE Webhook_Dispatcher SHALL send an HTTP POST request to the callback URL with the serialized Scrape_Result as the JSON request body.
3. THE Webhook_Dispatcher SHALL include a configurable HMAC signature header computed over the request body so that the receiver can verify authenticity.
4. IF the webhook delivery receives an HTTP 4xx or 5xx response, THEN THE Webhook_Dispatcher SHALL retry delivery using exponential backoff up to a configurable maximum retry count.
5. IF webhook delivery fails after all retries, THEN THE Webhook_Dispatcher SHALL log the delivery failure with the job identifier, callback URL, and final HTTP status code.
6. WHEN a Scrape_Job fails after all retries and the original Scrape_Job included a callback URL, THE Webhook_Dispatcher SHALL send a failure notification to the callback URL containing the job identifier, site identifier, and failure reason.

---

### Requirement 27: Proxy Health Monitoring

**User Story:** As a system operator, I want proxies that are being blocked by target sites detected and removed from rotation proactively, so that scrape jobs do not fail due to known-bad proxies.

#### Acceptance Criteria

1. THE Proxy_Health_Monitor SHALL track the success and failure count per proxy endpoint per target domain over a configurable sliding window.
2. WHEN the failure rate for a proxy endpoint on a specific domain exceeds a configurable threshold, THE Proxy_Health_Monitor SHALL mark that proxy as blocked for that domain and remove it from the Proxy_Pool rotation for that domain.
3. THE Proxy_Health_Monitor SHALL detect common blocking signals including HTTP 403 responses, CAPTCHA challenge pages, and connection resets, and classify them as proxy-block indicators.
4. WHEN a proxy is marked as blocked for a domain, THE Proxy_Health_Monitor SHALL schedule a re-check after a configurable cooldown period by routing a lightweight probe request through the proxy to the domain.
5. WHEN a re-check probe succeeds, THE Proxy_Health_Monitor SHALL restore the proxy to the Proxy_Pool rotation for that domain.
6. THE Proxy_Health_Monitor SHALL log each proxy block and restoration event at WARN level with the proxy endpoint, domain, and failure rate.
7. THE Metrics_Collector SHALL emit the count of healthy and blocked proxies per domain at the configurable reporting interval.

---

### Requirement 28: Session Warmup

**User Story:** As a system operator, I want the scraper to visit benign pages before navigating to the target URL, so that the browser session builds a realistic browsing profile that reduces detection risk.

#### Acceptance Criteria

1. WHEN a new browser context is created for a Scrape_Job, THE Session_Warmup_Engine SHALL navigate to a configurable sequence of benign warmup URLs before the Base_Scraper invokes the Site_Scraper extraction method.
2. THE Session_Warmup_Engine SHALL use the Human_Behavior_Engine to interact with warmup pages using human-like delays, scrolling, and mouse movements.
3. THE Session_Warmup_Engine SHALL accept cookies set by warmup pages and retain them in the browser context for the duration of the Scrape_Job.
4. THE configuration file SHALL support a configurable list of warmup URLs and a configurable minimum and maximum number of warmup pages to visit per session.
5. THE Session_Warmup_Engine SHALL select warmup pages randomly from the configured list, visiting between the configured minimum and maximum count.
6. IF a warmup page fails to load within a configurable timeout, THEN THE Session_Warmup_Engine SHALL skip that page, log a warning with the warmup URL, and continue with the next warmup page.
7. THE Session_Warmup_Engine SHALL complete all warmup navigation before the Base_Scraper applies stealth configurations and begins the extraction lifecycle.
