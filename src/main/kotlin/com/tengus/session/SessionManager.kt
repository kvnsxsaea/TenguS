package com.tengus.session

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-job cookie isolation using Playwright browser contexts.
 * Each job gets its own isolated BrowserContext with an empty cookie jar,
 * ensuring no cookie or session data leaks between concurrent jobs.
 *
 * Validates: Requirements 13.1, 13.2, 13.3, 13.4
 */
class SessionManager(private val browser: Browser) {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    private val sessions = ConcurrentHashMap<String, BrowserContext>()

    /**
     * Creates a new isolated Playwright BrowserContext for the given [jobId].
     * The context starts with an empty cookie jar dedicated to that job.
     *
     * Validates: Requirements 13.1, 13.2, 13.4
     *
     * @param jobId unique identifier for the scrape job
     * @return the newly created BrowserContext
     * @throws IllegalStateException if a session already exists for the given jobId
     */
    fun createSession(jobId: String): BrowserContext {
        require(jobId.isNotBlank()) { "jobId must not be blank" }

        check(!sessions.containsKey(jobId)) {
            "Session already exists for jobId: $jobId"
        }

        val context = browser.newContext()
        sessions[jobId] = context
        logger.debug("Created session for jobId={}", jobId)
        return context
    }

    /**
     * Closes the BrowserContext for the given [jobId], discarding all cookies
     * and session state. Removes it from the internal map.
     *
     * Validates: Requirements 13.3, 13.4
     *
     * @param jobId unique identifier for the scrape job
     */
    fun destroySession(jobId: String) {
        val context = sessions.remove(jobId)
        if (context != null) {
            context.close()
            logger.debug("Destroyed session for jobId={}", jobId)
        } else {
            logger.debug("No session found to destroy for jobId={}", jobId)
        }
    }
}
