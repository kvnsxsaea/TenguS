package com.tengus.behavior

import com.tengus.config.HumanBehaviorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class HumanBehaviorEngineTest : FunSpec({

    val config = HumanBehaviorConfig(
        initialDelayRange = 100L..200L,
        keystrokeDelayRange = 10L..20L,
        scrollPauseRange = 30L..50L,
        interActionDelayRange = 50L..100L
    )

    fun createEngine(seed: Int = 42) = HumanBehaviorEngine(config, Random(seed))

    // --- Requirement 11.1: randomDelay within configured bounds ---

    test("randomDelay sleeps within specified min/max range") {
        val engine = createEngine()
        val start = System.currentTimeMillis()
        engine.randomDelay(100L, 200L)
        val elapsed = System.currentTimeMillis() - start
        elapsed shouldBeGreaterThanOrEqual 100L
        elapsed shouldBeLessThanOrEqual 300L // allow some scheduling slack
    }

    test("randomDelay uses config defaults when no args provided") {
        val engine = createEngine()
        val start = System.currentTimeMillis()
        engine.randomDelay()
        val elapsed = System.currentTimeMillis() - start
        elapsed shouldBeGreaterThanOrEqual 100L
        elapsed shouldBeLessThanOrEqual 300L
    }

    test("randomDelay with equal min and max sleeps for that exact duration") {
        val engine = createEngine()
        val start = System.currentTimeMillis()
        engine.randomDelay(150L, 150L)
        val elapsed = System.currentTimeMillis() - start
        elapsed shouldBeGreaterThanOrEqual 150L
        elapsed shouldBeLessThanOrEqual 250L
    }

    // --- Requirement 11.5: interActionDelay within configured bounds ---

    test("interActionDelay sleeps within configured range") {
        val engine = createEngine()
        val start = System.currentTimeMillis()
        engine.interActionDelay()
        val elapsed = System.currentTimeMillis() - start
        elapsed shouldBeGreaterThanOrEqual 50L
        elapsed shouldBeLessThanOrEqual 200L
    }

    // --- Requirement 11.4: cubicBezier curve math ---

    test("cubicBezier at t=0 returns start point") {
        val engine = createEngine()
        engine.cubicBezier(10.0, 20.0, 30.0, 40.0, 0.0) shouldBe 10.0
    }

    test("cubicBezier at t=1 returns end point") {
        val engine = createEngine()
        engine.cubicBezier(10.0, 20.0, 30.0, 40.0, 1.0) shouldBe 40.0
    }

    test("cubicBezier at t=0.5 returns value between start and end") {
        val engine = createEngine()
        val result = engine.cubicBezier(0.0, 25.0, 75.0, 100.0, 0.5)
        result shouldBeGreaterThanOrEqual 0.0
        result shouldBeLessThanOrEqual 100.0
    }

    test("cubicBezier with all same points returns that point") {
        val engine = createEngine()
        val result = engine.cubicBezier(50.0, 50.0, 50.0, 50.0, 0.3)
        result shouldBeGreaterThanOrEqual 49.99
        result shouldBeLessThanOrEqual 50.01
    }

    // --- Multiple delay calls produce varying durations (randomness) ---

    test("multiple interActionDelay calls produce delays within range") {
        // Use different seeds to get different random values
        repeat(5) { i ->
            val engine = HumanBehaviorEngine(config, Random(i))
            val start = System.currentTimeMillis()
            engine.interActionDelay()
            val elapsed = System.currentTimeMillis() - start
            elapsed shouldBeGreaterThanOrEqual 50L
            elapsed shouldBeLessThanOrEqual 200L
        }
    }
})
