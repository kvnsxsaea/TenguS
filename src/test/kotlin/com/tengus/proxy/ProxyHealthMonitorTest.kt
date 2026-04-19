package com.tengus.proxy

import com.tengus.config.ProxyHealthConfig
import com.tengus.model.BlockingSignal
import com.tengus.model.ProxyEndpoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProxyHealthMonitorTest : FunSpec({

    val proxy1 = ProxyEndpoint("1.2.3.4", 8080)
    val proxy2 = ProxyEndpoint("5.6.7.8", 8080)
    val domain = "example.com"

    // 10-second window, 50% failure threshold, 5-second cooldown
    val config = ProxyHealthConfig(slidingWindowMs = 10_000, failureRateThreshold = 0.5, cooldownMs = 5_000)

    // --- Requirement 27.3: classifyBlockingSignal ---

    test("classifyBlockingSignal returns HTTP_403 for status 403") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(403, null) shouldBe BlockingSignal.HTTP_403
    }

    test("classifyBlockingSignal returns CAPTCHA_CHALLENGE when body contains captcha") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(200, "Please solve the CAPTCHA to continue") shouldBe BlockingSignal.CAPTCHA_CHALLENGE
    }

    test("classifyBlockingSignal returns CONNECTION_RESET when body contains connection reset") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(200, "Connection Reset by peer") shouldBe BlockingSignal.CONNECTION_RESET
    }

    test("classifyBlockingSignal returns null for normal responses") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(200, "OK") shouldBe null
    }

    test("classifyBlockingSignal returns null for null body with non-403 status") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(500, null) shouldBe null
    }

    test("classifyBlockingSignal prioritizes HTTP 403 over body content") {
        val monitor = ProxyHealthMonitor(config)
        monitor.classifyBlockingSignal(403, "captcha page") shouldBe BlockingSignal.HTTP_403
    }

    // --- Requirement 27.1 & 27.2: Sliding window tracking and isBlocked ---

    test("proxy is not blocked when all requests succeed") {
        val monitor = ProxyHealthMonitor(config)
        repeat(10) { monitor.recordSuccess(proxy1, domain) }
        monitor.isBlocked(proxy1, domain) shouldBe false
    }

    test("proxy is blocked when failure rate exceeds threshold") {
        val monitor = ProxyHealthMonitor(config)
        // 3 successes, 4 failures → 4/7 ≈ 57% > 50%
        repeat(3) { monitor.recordSuccess(proxy1, domain) }
        repeat(4) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe true
    }

    test("proxy is not blocked when failure rate is at threshold") {
        val monitor = ProxyHealthMonitor(config)
        // 5 successes, 5 failures → 50% = threshold (not exceeded)
        repeat(5) { monitor.recordSuccess(proxy1, domain) }
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe false
    }

    test("blocking is per proxy per domain") {
        val monitor = ProxyHealthMonitor(config)
        // Block proxy1 on domain
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe true
        // proxy2 on same domain is fine
        monitor.isBlocked(proxy2, domain) shouldBe false
        // proxy1 on different domain is fine
        monitor.isBlocked(proxy1, "other.com") shouldBe false
    }

    test("old events outside sliding window are pruned") {
        var now = 1_000_000L
        val monitor = ProxyHealthMonitor(config) { now }

        // Record failures at t=1_000_000
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe true

        // Advance time past the sliding window
        now += config.slidingWindowMs + 1

        // Old failures are pruned, proxy should no longer be blocked
        monitor.isBlocked(proxy1, domain) shouldBe false
    }

    // --- Requirement 27.4 & 27.5: scheduleRecheck and cooldown ---

    test("scheduleRecheck keeps proxy blocked during cooldown") {
        // Use a config where cooldown is longer than the sliding window
        // so we can advance past the window while still within cooldown
        val longCooldownConfig = ProxyHealthConfig(slidingWindowMs = 5_000, failureRateThreshold = 0.5, cooldownMs = 15_000)
        var now = 1_000_000L
        val monitor = ProxyHealthMonitor(longCooldownConfig) { now }

        // Block the proxy
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe true

        // Schedule recheck
        monitor.scheduleRecheck(proxy1, domain)

        // Advance time past sliding window but within cooldown
        now += longCooldownConfig.slidingWindowMs + 1
        // Without recheck schedule, events would be pruned and proxy unblocked.
        // But recheck cooldown keeps it blocked.
        monitor.isBlocked(proxy1, domain) shouldBe true

        // Advance past cooldown
        now = 1_000_000L + longCooldownConfig.cooldownMs + 1
        monitor.isBlocked(proxy1, domain) shouldBe false
    }

    // --- Requirement 27.5: Restoration after successful recheck ---

    test("recordSuccess restores a blocked proxy") {
        val monitor = ProxyHealthMonitor(config)
        // Block the proxy
        repeat(10) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        monitor.isBlocked(proxy1, domain) shouldBe true

        // Enough successes to bring failure rate below threshold
        repeat(20) { monitor.recordSuccess(proxy1, domain) }
        monitor.isBlocked(proxy1, domain) shouldBe false
    }
})
