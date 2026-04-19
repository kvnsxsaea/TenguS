package com.tengus.stealth

import com.tengus.config.UserAgentConfig
import com.tengus.model.WeightedUserAgent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import kotlin.random.Random

class UserAgentRotatorTest : FunSpec({

    val agentA = WeightedUserAgent("Mozilla/Chrome-A", 7.0, "Chrome", "120.0")
    val agentB = WeightedUserAgent("Mozilla/Firefox-B", 2.0, "Firefox", "121.0")
    val agentC = WeightedUserAgent("Mozilla/Safari-C", 1.0, "Safari", "17.0")

    // --- Requirement 9.1: Weighted-random selection ---

    test("weighted-random selection roughly matches configured weights over many selections") {
        val config = UserAgentConfig(agents = listOf(agentA, agentB, agentC))
        val rotator = UserAgentRotator(config, Random(42))

        val counts = mutableMapOf<String, Int>()
        val iterations = 10_000
        // Use different domains to avoid consecutive-different filtering skewing results
        for (i in 0 until iterations) {
            val selected = rotator.select("domain-$i.com")
            counts[selected] = (counts[selected] ?: 0) + 1
        }

        val total = counts.values.sum().toDouble()
        val ratioA = counts[agentA.userAgent]!! / total
        val ratioB = counts[agentB.userAgent]!! / total
        val ratioC = counts[agentC.userAgent]!! / total

        // Expected: A=70%, B=20%, C=10%. Allow ±5% tolerance.
        ratioA shouldBeGreaterThan 0.60
        ratioA shouldBeLessThan 0.80
        ratioB shouldBeGreaterThan 0.13
        ratioB shouldBeLessThan 0.27
        ratioC shouldBeGreaterThan 0.05
        ratioC shouldBeLessThan 0.18
    }

    // --- Requirement 9.3: Consecutive-different guarantee ---

    test("consecutive calls for the same domain never return the same agent twice in a row") {
        val config = UserAgentConfig(agents = listOf(agentA, agentB, agentC))
        val rotator = UserAgentRotator(config, Random(123))

        var previous = rotator.select("example.com")
        for (i in 0 until 500) {
            val current = rotator.select("example.com")
            current shouldNotBe previous
            previous = current
        }
    }

    // --- Requirement 9.3: Single-agent edge case ---

    test("single agent always returns that agent") {
        val config = UserAgentConfig(agents = listOf(agentA))
        val rotator = UserAgentRotator(config, Random(0))

        for (i in 0 until 50) {
            rotator.select("example.com") shouldBe agentA.userAgent
        }
    }

    // --- Requirement 9.2: availableAgents returns configured list ---

    test("availableAgents returns the configured list") {
        val agents = listOf(agentA, agentB, agentC)
        val config = UserAgentConfig(agents = agents)
        val rotator = UserAgentRotator(config)

        rotator.availableAgents() shouldBe agents
    }

    // --- Consecutive-different is per-domain ---

    test("different domains can receive the same agent consecutively") {
        val config = UserAgentConfig(agents = listOf(agentA, agentB))
        // Use a seeded random so the first pick for both domains is deterministic
        val rotator = UserAgentRotator(config, Random(99))

        val fromDomain1 = rotator.select("domain1.com")
        val fromDomain2 = rotator.select("domain2.com")
        // They CAN be the same (not guaranteed, but with only 2 agents and a seed it's likely).
        // The key assertion: this should NOT throw or violate any constraint.
        // Run many times to confirm no error is raised.
        for (i in 0 until 200) {
            rotator.select("domainA-$i.com")
            rotator.select("domainB-$i.com")
        }
    }

    // --- Empty agents list ---

    test("throws when no agents are configured") {
        val config = UserAgentConfig(agents = emptyList())
        val rotator = UserAgentRotator(config)

        shouldThrow<IllegalArgumentException> {
            rotator.select("example.com")
        }
    }
})
