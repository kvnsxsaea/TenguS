package com.tengus.session

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.config.WarmupConfig
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Navigates a sequence of benign pages before visiting the target URL to
 * establish a realistic browsing history and cookie profile within the
 * browser context. Cookies set by warmup pages are automatically retained
 * in the BrowserContext for the duration of the scrape job.
 *
 * All warmup navigation completes before stealth configuration and the
 * extraction lifecycle begins.
 *
 * Validates: Requirements 28.1, 28.2, 28.3, 28.4, 28.5, 28.6, 28.7
 */
class SessionWarmupEngine(
    private val humanBehaviorEngine: HumanBehaviorEngine,
    private val config: WarmupConfig,
    private val random: Random = Random.Default
) {
    private val logger = LoggerFactory.getLogger(SessionWarmupEngine::class.java)

    /**
     * Warms up the browser session by navigating to a random subset of
     * configured benign URLs and performing human-like interactions on each.
     *
     * 1. Randomly selects between [config.minPages] and [config.maxPages]
     *    URLs from [config.urls].
     * 2. For each selected URL, navigates with a timeout of [config.pageTimeoutMs].
     * 3. Uses [HumanBehaviorEngine] for human-like delays and scrolling.
     * 4. Cookies set by warmup pages are retained in the [context].
     * 5. Pages that fail to load are skipped with a warning log.
     *
     * @param context the Playwright BrowserContext (cookies accumulate here)
     * @param page    the Page instance to use for navigation
     */
    fun warmup(context: BrowserContext, page: Page) {
        if (config.urls.isEmpty()) {
            logger.debug("No warmup URLs configured, skipping warmup")
            return
        }

        val count = selectPageCount()
        val selectedUrls = selectUrls(count)

        logger.info("Starting session warmup: visiting {} of {} configured URLs", selectedUrls.size, config.urls.size)

        for (url in selectedUrls) {
            try {
                navigateAndInteract(page, url)
            } catch (e: Exception) {
                logger.warn("Warmup page failed to load within timeout, skipping: url={}, error={}", url, e.message)
            }
        }

        logger.info("Session warmup complete: visited {} warmup pages", selectedUrls.size)
    }

    /**
     * Selects a random count between [config.minPages] and [config.maxPages],
     * clamped to the actual number of available URLs.
     */
    internal fun selectPageCount(): Int {
        val min = config.minPages.coerceAtMost(config.urls.size)
        val max = config.maxPages.coerceAtMost(config.urls.size)
        if (min >= max) return min
        return random.nextInt(min, max + 1)
    }

    /**
     * Randomly selects [count] distinct URLs from the configured list.
     */
    internal fun selectUrls(count: Int): List<String> {
        return config.urls.shuffled(random).take(count)
    }

    /**
     * Navigates to [url] with the configured timeout, then performs
     * human-like interactions (random delay + scroll).
     */
    private fun navigateAndInteract(page: Page, url: String) {
        page.navigate(url, Page.NavigateOptions()
            .setTimeout(config.pageTimeoutMs.toDouble())
            .setWaitUntil(WaitUntilState.LOAD))

        logger.debug("Warmup page loaded: {}", url)

        // Human-like interactions on the warmup page
        humanBehaviorEngine.randomDelay()
        humanBehaviorEngine.scrollPage(page)
        humanBehaviorEngine.interActionDelay()
    }
}
