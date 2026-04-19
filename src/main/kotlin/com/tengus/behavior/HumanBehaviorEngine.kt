package com.tengus.behavior

import com.microsoft.playwright.Page
import com.tengus.config.HumanBehaviorConfig
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Provides human-like interaction utilities for browser automation:
 * random delays, realistic typing, incremental scrolling, Bezier-curve
 * mouse movement, and natural click behavior.
 *
 * Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5
 */
class HumanBehaviorEngine(
    private val config: HumanBehaviorConfig,
    private val random: Random = Random.Default
) {
    private val logger = LoggerFactory.getLogger(HumanBehaviorEngine::class.java)

    /**
     * Sleeps for a random duration between [minMs] and [maxMs] milliseconds.
     * Used to introduce human-like pauses before interactions.
     *
     * Validates: Requirements 11.1
     */
    fun randomDelay(minMs: Long = config.initialDelayRange.first, maxMs: Long = config.initialDelayRange.last) {
        require(minMs >= 0) { "minMs must be non-negative, was $minMs" }
        require(maxMs >= minMs) { "maxMs ($maxMs) must be >= minMs ($minMs)" }
        val delayMs = random.nextLong(minMs, maxMs + 1)
        logger.debug("Random delay: {}ms (range {}–{}ms)", delayMs, minMs, maxMs)
        Thread.sleep(delayMs)
    }

    /**
     * Types [text] into the element matching [selector] one character at a time,
     * with a random inter-keystroke delay from [config.keystrokeDelayRange].
     *
     * Validates: Requirements 11.2
     */
    fun typeText(page: Page, selector: String, text: String) {
        logger.debug("Typing {} characters into '{}'", text.length, selector)
        page.click(selector)
        for (char in text) {
            page.keyboard().press(char.toString())
            val keystrokeDelay = random.nextLong(
                config.keystrokeDelayRange.first,
                config.keystrokeDelayRange.last + 1
            )
            Thread.sleep(keystrokeDelay)
        }
        logger.debug("Finished typing into '{}'", selector)
    }

    /**
     * Scrolls the page in small increments (100–300px) with random pauses
     * from [config.scrollPauseRange] between each scroll step, simulating
     * human reading behavior.
     *
     * Validates: Requirements 11.3
     */
    fun scrollPage(page: Page) {
        val viewportHeight = page.viewportSize().height
        val totalHeight = page.evaluate("() => document.body.scrollHeight") as Number
        var scrolled = 0.0
        val target = totalHeight.toDouble()

        logger.debug("Scrolling page: viewport={}px, totalHeight={}px", viewportHeight, target)

        while (scrolled < target - viewportHeight) {
            val increment = random.nextInt(100, 301)
            page.mouse().wheel(0.0, increment.toDouble())
            scrolled += increment

            val pause = random.nextLong(
                config.scrollPauseRange.first,
                config.scrollPauseRange.last + 1
            )
            Thread.sleep(pause)
        }
        logger.debug("Finished scrolling page")
    }

    /**
     * Moves the mouse to coordinates ([x], [y]) using intermediate points
     * along a randomized cubic Bezier curve, producing a natural-looking trajectory.
     *
     * Validates: Requirements 11.4
     */
    fun moveMouse(page: Page, x: Double, y: Double) {
        val mouse = page.mouse()

        // Current position defaults to (0,0) if unknown; Playwright doesn't expose it,
        // so we track from a reasonable origin.
        val startX = 0.0
        val startY = 0.0

        // Generate two random control points for a cubic Bezier curve
        val cp1x = startX + (x - startX) * random.nextDouble(0.2, 0.5) + random.nextDouble(-50.0, 50.0)
        val cp1y = startY + (y - startY) * random.nextDouble(0.2, 0.5) + random.nextDouble(-50.0, 50.0)
        val cp2x = startX + (x - startX) * random.nextDouble(0.5, 0.8) + random.nextDouble(-30.0, 30.0)
        val cp2y = startY + (y - startY) * random.nextDouble(0.5, 0.8) + random.nextDouble(-30.0, 30.0)

        val steps = random.nextInt(10, 25)
        logger.debug("Moving mouse to ({}, {}) via Bezier curve with {} steps", x, y, steps)

        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val px = cubicBezier(startX, cp1x, cp2x, x, t)
            val py = cubicBezier(startY, cp1y, cp2y, y, t)
            mouse.move(px, py)
            Thread.sleep(random.nextLong(5, 20))
        }
        logger.debug("Mouse arrived at ({}, {})", x, y)
    }

    /**
     * Locates the element matching [selector], computes its bounding box center,
     * moves the mouse there via [moveMouse], then clicks.
     *
     * Validates: Requirements 11.4
     */
    fun clickElement(page: Page, selector: String) {
        val element = page.querySelector(selector)
            ?: throw IllegalArgumentException("Element not found for selector: $selector")

        val box = element.boundingBox()
            ?: throw IllegalStateException("Bounding box unavailable for selector: $selector")

        val centerX = box.x + box.width / 2
        val centerY = box.y + box.height / 2

        logger.debug("Clicking element '{}' at center ({}, {})", selector, centerX, centerY)
        moveMouse(page, centerX, centerY)
        page.mouse().click(centerX, centerY)
        logger.debug("Clicked element '{}'", selector)
    }

    /**
     * Introduces a random delay between sequential page interactions,
     * using [config.interActionDelayRange].
     *
     * Validates: Requirements 11.5
     */
    fun interActionDelay() {
        val delayMs = random.nextLong(
            config.interActionDelayRange.first,
            config.interActionDelayRange.last + 1
        )
        logger.debug("Inter-action delay: {}ms", delayMs)
        Thread.sleep(delayMs)
    }

    /**
     * Evaluates a point on a cubic Bezier curve at parameter [t] (0..1).
     */
    internal fun cubicBezier(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * p0 +
            3 * u * u * t * p1 +
            3 * u * t * t * p2 +
            t * t * t * p3
    }
}
