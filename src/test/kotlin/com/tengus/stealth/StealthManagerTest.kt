package com.tengus.stealth

import com.tengus.config.StealthConfig
import com.tengus.config.UserAgentConfig
import com.tengus.model.FingerprintProfile
import com.tengus.model.ViewportSize
import com.tengus.model.WeightedUserAgent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.random.Random

class StealthManagerTest : FunSpec({

    val chromeAgent = WeightedUserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        7.0, "Chrome", "120.0"
    )
    val firefoxAgent = WeightedUserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        2.0, "Firefox", "121.0"
    )
    val safariAgent = WeightedUserAgent(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        1.0, "Safari", "17.0"
    )

    val stealthConfig = StealthConfig(
        viewports = listOf(ViewportSize(1920, 1080), ViewportSize(1366, 768), ViewportSize(1440, 900)),
        timezones = listOf("America/New_York", "Europe/London", "Asia/Tokyo"),
        languages = listOf("en-US", "en-GB", "ja-JP"),
        platforms = listOf("Win32", "MacIntel", "Linux x86_64"),
        webglVendors = listOf("Google Inc. (NVIDIA)", "Google Inc. (Intel)"),
        webglRenderers = listOf("ANGLE (NVIDIA GeForce GTX 1080)", "ANGLE (Intel HD Graphics 630)")
    )

    val uaConfig = UserAgentConfig(agents = listOf(chromeAgent, firefoxAgent, safariAgent))
    val rotator = UserAgentRotator(uaConfig, Random(42))

    fun createManager(seed: Int = 42) = StealthManager(rotator, stealthConfig, Random(seed))

    // --- Requirement 6.5: Fingerprint profile consistency ---

    test("generateFingerprintProfile returns profile with all fields populated") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(chromeAgent.userAgent)

        profile.userAgent shouldBe chromeAgent.userAgent
        profile.viewport shouldNotBe null
        profile.timezone.shouldNotBeBlank()
        profile.language.shouldNotBeBlank()
        profile.platform.shouldNotBeBlank()
        profile.webglVendor.shouldNotBeBlank()
        profile.webglRenderer.shouldNotBeBlank()
    }

    test("generateFingerprintProfile selects viewport from config list") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(chromeAgent.userAgent)
        stealthConfig.viewports shouldContain profile.viewport
    }

    test("generateFingerprintProfile selects timezone from config list") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(chromeAgent.userAgent)
        stealthConfig.timezones shouldContain profile.timezone
    }

    test("generateFingerprintProfile selects language from config list") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(chromeAgent.userAgent)
        stealthConfig.languages shouldContain profile.language
    }

    // --- Requirement 6.2, 6.5: Platform consistency with browser family ---

    test("Chrome user-agent gets Win32, MacIntel, or Linux platform") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(chromeAgent.userAgent)
        profile.platform shouldBe profile.platform // just ensure it's one of the valid ones
        listOf("Win32", "MacIntel", "Linux x86_64") shouldContain profile.platform
    }

    test("Safari user-agent gets MacIntel platform") {
        val manager = createManager()
        val profile = manager.generateFingerprintProfile(safariAgent.userAgent)
        profile.platform shouldBe "MacIntel"
    }

    // --- Requirement 6.1: detectBrowserFamily ---

    test("detectBrowserFamily identifies Chrome") {
        val manager = createManager()
        manager.detectBrowserFamily(chromeAgent.userAgent) shouldBe "chrome"
    }

    test("detectBrowserFamily identifies Firefox") {
        val manager = createManager()
        manager.detectBrowserFamily(firefoxAgent.userAgent) shouldBe "firefox"
    }

    test("detectBrowserFamily identifies Safari") {
        val manager = createManager()
        manager.detectBrowserFamily(safariAgent.userAgent) shouldBe "safari"
    }

    // --- Requirement 8.1, 8.2: Header normalization ---

    test("normalizeHeaders for Chrome includes Sec-CH-UA and standard Sec-Fetch headers") {
        val manager = createManager()
        val headers = manager.buildNormalizedHeaders("chrome", chromeAgent.userAgent)

        headers["Accept"] shouldNotBe null
        headers["Accept-Language"] shouldNotBe null
        headers["Accept-Encoding"] shouldBe "gzip, deflate, br"
        headers["Sec-CH-UA"] shouldNotBe null
        headers["Sec-CH-UA"]!! shouldContain "Chromium"
        headers["Sec-CH-UA"]!! shouldContain "120"
        headers["Sec-Fetch-Site"] shouldBe "none"
        headers["Sec-Fetch-Mode"] shouldBe "navigate"
        headers["Sec-Fetch-User"] shouldBe "?1"
        headers["Sec-Fetch-Dest"] shouldBe "document"
    }

    test("normalizeHeaders for Firefox omits Sec-CH-UA headers") {
        val manager = createManager()
        val headers = manager.buildNormalizedHeaders("firefox", firefoxAgent.userAgent)

        headers["Accept"] shouldNotBe null
        headers["Accept-Encoding"] shouldBe "gzip, deflate, br"
        headers.containsKey("Sec-CH-UA") shouldBe false
        // Sec-Fetch headers should still be present
        headers["Sec-Fetch-Site"] shouldBe "none"
        headers["Sec-Fetch-Mode"] shouldBe "navigate"
    }

    test("normalizeHeaders for Safari omits Sec-CH-UA headers") {
        val manager = createManager()
        val headers = manager.buildNormalizedHeaders("safari", safariAgent.userAgent)

        headers.containsKey("Sec-CH-UA") shouldBe false
        headers["Sec-Fetch-Site"] shouldBe "none"
    }

    test("normalizeHeaders sets User-Agent header") {
        val manager = createManager()
        val headers = manager.buildNormalizedHeaders("chrome", chromeAgent.userAgent)
        headers["User-Agent"] shouldBe chromeAgent.userAgent
    }

    // --- Requirement 8.3: No extraneous headers ---

    test("normalizeHeaders for Chrome includes Sec-CH-UA-Mobile and Sec-CH-UA-Platform") {
        val manager = createManager()
        val headers = manager.buildNormalizedHeaders("chrome", chromeAgent.userAgent)
        headers["Sec-CH-UA-Mobile"] shouldBe "?0"
        headers["Sec-CH-UA-Platform"] shouldNotBe null
    }

    // --- Requirement 7.1, 7.2, 7.3, 7.4: Bot detection patches are well-formed JS ---

    test("webdriver patch contains navigator.webdriver override") {
        val manager = createManager()
        val script = manager.buildWebdriverPatch()
        script shouldContain "navigator"
        script shouldContain "webdriver"
        script shouldContain "false"
    }

    test("Chrome DevTools patch sets window.chrome") {
        val manager = createManager()
        val script = manager.buildChromeDevToolsPatch()
        script shouldContain "window.chrome"
        script shouldContain "runtime"
        script shouldContain "loadTimes"
        script shouldContain "csi"
    }

    test("plugins patch overrides navigator.plugins and navigator.mimeTypes") {
        val manager = createManager()
        val script = manager.buildPluginsPatch()
        script shouldContain "navigator"
        script shouldContain "plugins"
        script shouldContain "mimeTypes"
        script shouldContain "Chrome PDF"
    }

    test("permissions patch overrides navigator.permissions.query") {
        val manager = createManager()
        val script = manager.buildPermissionsPatch()
        script shouldContain "permissions"
        script shouldContain "query"
        script shouldContain "notifications"
    }

    // --- Requirement 6.3: WebGL override script ---

    test("WebGL override script targets correct parameter constants") {
        val manager = createManager()
        val script = manager.buildWebGLOverrideScript("TestVendor", "TestRenderer")
        script shouldContain "37445"  // UNMASKED_VENDOR_WEBGL
        script shouldContain "37446"  // UNMASKED_RENDERER_WEBGL
        script shouldContain "TestVendor"
        script shouldContain "TestRenderer"
    }

    // --- Requirement 6.4: Canvas noise injection ---

    test("canvas noise script overrides toDataURL and toBlob") {
        val manager = createManager()
        val script = manager.buildCanvasNoiseScript()
        script shouldContain "toDataURL"
        script shouldContain "toBlob"
        script shouldContain "getImageData"
    }

    // --- extractMajorVersion ---

    test("extractMajorVersion extracts Chrome version") {
        val manager = createManager()
        manager.extractMajorVersion(chromeAgent.userAgent, "chrome") shouldBe "120"
    }

    test("extractMajorVersion returns default for unknown UA") {
        val manager = createManager()
        manager.extractMajorVersion("SomeUnknownBrowser/1.0", "chrome") shouldBe "120"
    }
})
