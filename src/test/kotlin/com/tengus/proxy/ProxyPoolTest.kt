package com.tengus.proxy

import com.tengus.config.ProxyConfig
import com.tengus.config.ProxyHealthConfig
import com.tengus.model.BlockingSignal
import com.tengus.model.ProxyEndpoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class ProxyPoolTest : FunSpec({

    val proxy1 = ProxyEndpoint("1.2.3.4", 8080)
    val proxy2 = ProxyEndpoint("5.6.7.8", 8080)
    val proxy3 = ProxyEndpoint("9.10.11.12", 8080)
    val domain = "example.com"

    val healthConfig = ProxyHealthConfig(slidingWindowMs = 10_000, failureRateThreshold = 0.5, cooldownMs = 5_000)

    fun proxyConfig(endpoints: List<ProxyEndpoint> = listOf(proxy1, proxy2, proxy3)) =
        ProxyConfig(endpoints = endpoints, connectTimeoutMs = 5000, healthCheckIntervalMs = 30_000)

    // --- Requirement 10.1: Proxy selection ---

    test("selectProxy returns a proxy from configured endpoints") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(), monitor)
        val selected = pool.selectProxy(domain)
        selected shouldBe proxy1  // first call returns first candidate
    }

    // --- Requirement 10.2: Consecutive-different rotation ---

    test("selectProxy rotates so consecutive calls for same domain use different proxies") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(), monitor)

        val first = pool.selectProxy(domain)
        val second = pool.selectProxy(domain)
        first shouldNotBe second
    }

    test("selectProxy returns same proxy when only one is available") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1)), monitor)

        val first = pool.selectProxy(domain)
        val second = pool.selectProxy(domain)
        first shouldBe second
        first shouldBe proxy1
    }

    test("selectProxy rotates across different domains independently") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        val domainA = "a.com"
        val domainB = "b.com"

        val a1 = pool.selectProxy(domainA)
        val b1 = pool.selectProxy(domainB)
        // Both domains start fresh, so both get the first candidate
        a1 shouldBe proxy1
        b1 shouldBe proxy1

        // Second call for each domain should rotate
        val a2 = pool.selectProxy(domainA)
        val b2 = pool.selectProxy(domainB)
        a2 shouldBe proxy2
        b2 shouldBe proxy2
    }

    // --- Requirement 10.3: markUnhealthy ---

    test("markUnhealthy removes proxy from selection") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        pool.markUnhealthy(proxy1)

        val selected = pool.selectProxy(domain)
        selected shouldBe proxy2
    }

    // --- Requirement 10.4: restoreProxy ---

    test("restoreProxy returns proxy to rotation pool") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        pool.markUnhealthy(proxy1)
        pool.selectProxy(domain) shouldBe proxy2

        pool.restoreProxy(proxy1)
        // Now both proxies are available; rotation should pick proxy1 (different from last used proxy2)
        pool.selectProxy(domain) shouldBe proxy1
    }

    // --- Requirement 10.5: All proxies unhealthy ---

    test("selectProxy throws when all proxies are globally unhealthy") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        pool.markUnhealthy(proxy1)
        pool.markUnhealthy(proxy2)

        val ex = shouldThrow<IllegalStateException> {
            pool.selectProxy(domain)
        }
        ex.message shouldContain "No healthy proxies available"
        ex.message shouldContain domain
    }

    // --- Integration with ProxyHealthMonitor ---

    test("selectProxy excludes proxies blocked by health monitor for specific domain") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        // Block proxy1 on this domain via health monitor
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }

        val selected = pool.selectProxy(domain)
        selected shouldBe proxy2
    }

    test("selectProxy throws when all proxies blocked by health monitor for domain") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        // Block both proxies on this domain
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }
        repeat(5) { monitor.recordFailure(proxy2, domain, BlockingSignal.CAPTCHA_CHALLENGE) }

        shouldThrow<IllegalStateException> {
            pool.selectProxy(domain)
        }
    }

    test("proxy blocked on one domain is still available for another domain") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(listOf(proxy1, proxy2)), monitor)

        // Block proxy1 on domain
        repeat(5) { monitor.recordFailure(proxy1, domain, BlockingSignal.HTTP_403) }

        // proxy1 should still be available for other.com
        val candidates = pool.healthyProxiesForDomain("other.com")
        candidates shouldContainExactlyInAnyOrder listOf(proxy1, proxy2)
    }

    // --- healthyProxiesForDomain ---

    test("healthyProxiesForDomain returns all proxies when none are unhealthy or blocked") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(), monitor)

        pool.healthyProxiesForDomain(domain) shouldContainExactlyInAnyOrder listOf(proxy1, proxy2, proxy3)
    }

    test("healthyProxiesForDomain excludes both globally unhealthy and domain-blocked proxies") {
        val monitor = ProxyHealthMonitor(healthConfig)
        val pool = ProxyPool(proxyConfig(), monitor)

        pool.markUnhealthy(proxy1)
        repeat(5) { monitor.recordFailure(proxy2, domain, BlockingSignal.HTTP_403) }

        pool.healthyProxiesForDomain(domain) shouldBe listOf(proxy3)
    }
})
