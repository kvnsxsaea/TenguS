package com.tengus.session

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import com.tengus.behavior.HumanBehaviorEngine
import com.tengus.config.HumanBehaviorConfig
import com.tengus.config.WarmupConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class SessionWarmupEngineTest : FunSpec({

    val warmupUrls = listOf(
        "https://example.com",
        "https://wikipedia.org",
        "https://google.com",
        "https://github.com",
        "https://stackoverflow.com"
    )

    fun createConfig(
        urls: List<String> = warmupUrls,
        minPages: Int = 2,
        maxPages: Int = 4,
        pageTimeoutMs: Long = 5000L
    ) = WarmupConfig(urls = urls, minPages = minPages, maxPages = maxPages, pageTimeoutMs = pageTimeoutMs)

    fun createEngine(config: WarmupConfig, seed: Int = 42): SessionWarmupEngine {
        val behaviorConfig = HumanBehaviorConfig(
            initialDelayRange = 1L..2L,
            keystrokeDelayRange = 1L..2L,
            scrollPauseRange = 1L..2L,
            interActionDelayRange = 1L..2L
        )
        val humanBehaviorEngine = HumanBehaviorEngine(behaviorConfig, Random(seed))
        return SessionWarmupEngine(humanBehaviorEngine, config, Random(seed))
    }

    // --- Requirement 28.5: selectPageCount respects min/max ---

    test("selectPageCount returns value between minPages and maxPages") {
        val config = createConfig(minPages = 2, maxPages = 4)
        repeat(20) { i ->
            val engine = createEngine(config, seed = i)
            val count = engine.selectPageCount()
            count shouldBeGreaterThanOrEqual 2
            count shouldBeLessThanOrEqual 4
        }
    }

    test("selectPageCount clamps to available URL count when maxPages exceeds urls size") {
        val config = createConfig(urls = listOf("https://a.com", "https://b.com"), minPages = 1, maxPages = 10)
        val engine = createEngine(config)
        val count = engine.selectPageCount()
        count shouldBeLessThanOrEqual 2
    }

    test("selectPageCount returns minPages when min equals max") {
        val config = createConfig(minPages = 3, maxPages = 3)
        val engine = createEngine(config)
        engine.selectPageCount() shouldBe 3
    }

    test("selectPageCount clamps minPages to urls size") {
        val config = createConfig(urls = listOf("https://a.com"), minPages = 5, maxPages = 10)
        val engine = createEngine(config)
        engine.selectPageCount() shouldBe 1
    }

    // --- Requirement 28.5: selectUrls picks correct count ---

    test("selectUrls returns requested number of distinct URLs") {
        val config = createConfig()
        val engine = createEngine(config)
        val selected = engine.selectUrls(3)
        selected shouldHaveSize 3
        selected.distinct() shouldHaveSize 3
    }

    test("selectUrls returns all URLs when count equals list size") {
        val config = createConfig()
        val engine = createEngine(config)
        val selected = engine.selectUrls(warmupUrls.size)
        selected shouldHaveSize warmupUrls.size
    }

    test("selectUrls returns empty list when count is 0") {
        val config = createConfig()
        val engine = createEngine(config)
        val selected = engine.selectUrls(0)
        selected shouldHaveSize 0
    }

    // --- Requirement 28.5: randomness in URL selection ---

    test("selectUrls produces different orderings with different seeds") {
        val config = createConfig()
        val engine1 = createEngine(config, seed = 1)
        val engine2 = createEngine(config, seed = 99)
        val selected1 = engine1.selectUrls(5)
        val selected2 = engine2.selectUrls(5)
        // Same elements, potentially different order
        selected1.toSet() shouldBe selected2.toSet()
    }

    // --- Requirement 28.4: empty URL list ---

    test("selectPageCount returns 0 when urls list is empty") {
        val config = createConfig(urls = emptyList(), minPages = 2, maxPages = 4)
        val engine = createEngine(config)
        engine.selectPageCount() shouldBe 0
    }
})
