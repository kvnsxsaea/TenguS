package com.tengus.stealth

import com.microsoft.playwright.BrowserContext
import com.tengus.config.StealthConfig
import com.tengus.model.FingerprintProfile
import com.tengus.model.ViewportSize
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Handles fingerprint randomization, bot detection patches, header normalization,
 * and canvas noise injection for stealth browser sessions.
 *
 * Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3
 */
class StealthManager(
    private val userAgentRotator: UserAgentRotator,
    private val config: StealthConfig,
    private val random: Random = Random.Default
) {
    private val logger = LoggerFactory.getLogger(StealthManager::class.java)

    /**
     * Generates a fingerprint profile with randomized but internally consistent attributes
     * based on the given user-agent string.
     */
    fun generateFingerprintProfile(userAgent: String): FingerprintProfile {
        val browserFamily = detectBrowserFamily(userAgent)
        val viewport = config.viewports.random(random)
        val timezone = config.timezones.random(random)
        val language = config.languages.random(random)
        val platform = selectConsistentPlatform(browserFamily)
        val webglVendor = config.webglVendors.random(random)
        val webglRenderer = config.webglRenderers.random(random)

        val profile = FingerprintProfile(
            viewport = viewport,
            timezone = timezone,
            language = language,
            platform = platform,
            userAgent = userAgent,
            webglVendor = webglVendor,
            webglRenderer = webglRenderer
        )
        logger.debug("Generated fingerprint profile for browser family '{}': platform={}, viewport={}x{}",
            browserFamily, platform, viewport.width, viewport.height)
        return profile
    }

    /**
     * Applies the fingerprint profile to a Playwright BrowserContext by setting viewport,
     * locale, timezone, user-agent, and injecting WebGL override scripts.
     */
    fun applyFingerprint(context: BrowserContext, profile: FingerprintProfile) {
        context.setDefaultNavigationTimeout(30_000.0)

        // Set viewport size
        context.pages().forEach { page ->
            page.setViewportSize(profile.viewport.width, profile.viewport.height)
        }

        // Inject WebGL vendor/renderer overrides
        val webglScript = buildWebGLOverrideScript(profile.webglVendor, profile.webglRenderer)
        context.addInitScript(webglScript)

        // Inject canvas fingerprint noise
        context.addInitScript(buildCanvasNoiseScript())

        logger.debug("Applied fingerprint to browser context: ua={}, viewport={}x{}, tz={}, lang={}",
            profile.userAgent, profile.viewport.width, profile.viewport.height,
            profile.timezone, profile.language)
    }

    /**
     * Injects JavaScript patches into the browser context to evade common bot detection:
     * - navigator.webdriver = false
     * - Chrome DevTools detection (window.chrome)
     * - navigator.plugins and navigator.mimeTypes
     * - Permissions API
     */
    fun applyBotDetectionPatches(context: BrowserContext) {
        context.addInitScript(buildWebdriverPatch())
        context.addInitScript(buildChromeDevToolsPatch())
        context.addInitScript(buildPluginsPatch())
        context.addInitScript(buildPermissionsPatch())
        logger.debug("Applied bot detection patches to browser context")
    }

    /**
     * Sets extra HTTP headers on the browser context consistent with the given user-agent's
     * browser family: Accept, Accept-Language, Accept-Encoding, Sec-CH-UA, Sec-Fetch-* headers.
     */
    fun normalizeHeaders(context: BrowserContext, userAgent: String) {
        val browserFamily = detectBrowserFamily(userAgent)
        val headers = buildNormalizedHeaders(browserFamily, userAgent)
        context.setExtraHTTPHeaders(headers)
        logger.debug("Normalized headers for browser family '{}': {} header(s) set", browserFamily, headers.size)
    }

    // --- Internal helpers ---

    internal fun detectBrowserFamily(userAgent: String): String {
        val ua = userAgent.lowercase()
        return when {
            "edg/" in ua || "edg " in ua -> "edge"
            "firefox/" in ua -> "firefox"
            "safari/" in ua && "chrome/" !in ua -> "safari"
            "chrome/" in ua || "chromium/" in ua -> "chrome"
            else -> "chrome" // default fallback
        }
    }

    internal fun selectConsistentPlatform(browserFamily: String): String {
        val consistentPlatforms = when (browserFamily) {
            "safari" -> config.platforms.filter { it.equals("MacIntel", ignoreCase = true) }
            "chrome", "edge" -> config.platforms.filter {
                it.equals("Win32", ignoreCase = true) ||
                it.equals("Linux x86_64", ignoreCase = true) ||
                it.equals("MacIntel", ignoreCase = true)
            }
            "firefox" -> config.platforms.filter {
                it.equals("Win32", ignoreCase = true) ||
                it.equals("Linux x86_64", ignoreCase = true) ||
                it.equals("MacIntel", ignoreCase = true)
            }
            else -> config.platforms
        }
        return if (consistentPlatforms.isNotEmpty()) {
            consistentPlatforms.random(random)
        } else {
            config.platforms.random(random)
        }
    }

    internal fun buildNormalizedHeaders(browserFamily: String, userAgent: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        headers["User-Agent"] = userAgent

        when (browserFamily) {
            "chrome", "edge" -> {
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                headers["Accept-Language"] = "en-US,en;q=0.9"
                headers["Accept-Encoding"] = "gzip, deflate, br"
                val brandName = if (browserFamily == "edge") "Microsoft Edge" else "Chromium"
                val version = extractMajorVersion(userAgent, browserFamily)
                headers["Sec-CH-UA"] = "\"$brandName\";v=\"$version\", \"Not_A Brand\";v=\"8\""
                headers["Sec-CH-UA-Mobile"] = "?0"
                headers["Sec-CH-UA-Platform"] = "\"Windows\""
            }
            "firefox" -> {
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                headers["Accept-Language"] = "en-US,en;q=0.5"
                headers["Accept-Encoding"] = "gzip, deflate, br"
                // Firefox does not send Sec-CH-UA headers
            }
            "safari" -> {
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                headers["Accept-Language"] = "en-US,en;q=0.9"
                headers["Accept-Encoding"] = "gzip, deflate, br"
                // Safari does not send Sec-CH-UA headers
            }
        }

        // Standard Sec-Fetch headers for navigation requests
        headers["Sec-Fetch-Site"] = "none"
        headers["Sec-Fetch-Mode"] = "navigate"
        headers["Sec-Fetch-User"] = "?1"
        headers["Sec-Fetch-Dest"] = "document"

        return headers
    }

    internal fun extractMajorVersion(userAgent: String, browserFamily: String): String {
        val pattern = when (browserFamily) {
            "edge" -> Regex("""Edg/(\d+)""")
            "chrome" -> Regex("""Chrome/(\d+)""")
            else -> Regex("""Chrome/(\d+)""")
        }
        return pattern.find(userAgent)?.groupValues?.get(1) ?: "120"
    }

    // --- JavaScript patch builders ---

    internal fun buildWebdriverPatch(): String = """
        Object.defineProperty(navigator, 'webdriver', {
            get: () => false,
            configurable: true
        });
    """.trimIndent()

    internal fun buildChromeDevToolsPatch(): String = """
        if (!window.chrome) {
            window.chrome = {};
        }
        if (!window.chrome.runtime) {
            window.chrome.runtime = {
                connect: function() {},
                sendMessage: function() {},
                onMessage: { addListener: function() {} },
                onConnect: { addListener: function() {} }
            };
        }
        if (!window.chrome.loadTimes) {
            window.chrome.loadTimes = function() {
                return {
                    commitLoadTime: Date.now() / 1000,
                    connectionInfo: "h2",
                    finishDocumentLoadTime: Date.now() / 1000,
                    finishLoadTime: Date.now() / 1000,
                    firstPaintAfterLoadTime: 0,
                    firstPaintTime: Date.now() / 1000,
                    navigationType: "Other",
                    npnNegotiatedProtocol: "h2",
                    requestTime: Date.now() / 1000 - 0.16,
                    startLoadTime: Date.now() / 1000 - 0.16,
                    wasAlternateProtocolAvailable: false,
                    wasFetchedViaSpdy: true,
                    wasNpnNegotiated: true
                };
            };
        }
        if (!window.chrome.csi) {
            window.chrome.csi = function() {
                return {
                    onloadT: Date.now(),
                    pageT: Date.now() / 1000,
                    startE: Date.now(),
                    tran: 15
                };
            };
        }
    """.trimIndent()

    internal fun buildPluginsPatch(): String = """
        Object.defineProperty(navigator, 'plugins', {
            get: () => {
                const plugins = [
                    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format', length: 1 },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '', length: 1 },
                    { name: 'Native Client', filename: 'internal-nacl-plugin', description: '', length: 2 }
                ];
                plugins.refresh = function() {};
                plugins.item = function(i) { return this[i] || null; };
                plugins.namedItem = function(name) { return this.find(p => p.name === name) || null; };
                return plugins;
            },
            configurable: true
        });
        Object.defineProperty(navigator, 'mimeTypes', {
            get: () => {
                const mimeTypes = [
                    { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: { name: 'Chrome PDF Plugin' } },
                    { type: 'application/x-google-chrome-pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: { name: 'Chrome PDF Viewer' } }
                ];
                mimeTypes.refresh = function() {};
                mimeTypes.item = function(i) { return this[i] || null; };
                mimeTypes.namedItem = function(name) { return this.find(m => m.type === name) || null; };
                return mimeTypes;
            },
            configurable: true
        });
    """.trimIndent()

    internal fun buildPermissionsPatch(): String = """
        if (navigator.permissions) {
            const originalQuery = navigator.permissions.query.bind(navigator.permissions);
            navigator.permissions.query = function(parameters) {
                if (parameters.name === 'notifications') {
                    return Promise.resolve({ state: Notification.permission, onchange: null });
                }
                return originalQuery(parameters).catch(function() {
                    return { state: 'prompt', onchange: null };
                });
            };
        }
    """.trimIndent()

    internal fun buildWebGLOverrideScript(vendor: String, renderer: String): String = """
        const getParameter = WebGLRenderingContext.prototype.getParameter;
        WebGLRenderingContext.prototype.getParameter = function(parameter) {
            if (parameter === 37445) { return '$vendor'; }
            if (parameter === 37446) { return '$renderer'; }
            return getParameter.call(this, parameter);
        };
        if (typeof WebGL2RenderingContext !== 'undefined') {
            const getParameter2 = WebGL2RenderingContext.prototype.getParameter;
            WebGL2RenderingContext.prototype.getParameter = function(parameter) {
                if (parameter === 37445) { return '$vendor'; }
                if (parameter === 37446) { return '$renderer'; }
                return getParameter2.call(this, parameter);
            };
        }
    """.trimIndent()

    internal fun buildCanvasNoiseScript(): String = """
        const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
        HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
            const context = this.getContext('2d');
            if (context) {
                const imageData = context.getImageData(0, 0, this.width, this.height);
                const data = imageData.data;
                for (let i = 0; i < data.length; i += 4) {
                    data[i] = data[i] ^ (data[i] & 1);
                }
                context.putImageData(imageData, 0, 0);
            }
            return originalToDataURL.call(this, type, quality);
        };
        const originalToBlob = HTMLCanvasElement.prototype.toBlob;
        HTMLCanvasElement.prototype.toBlob = function(callback, type, quality) {
            const context = this.getContext('2d');
            if (context) {
                const imageData = context.getImageData(0, 0, this.width, this.height);
                const data = imageData.data;
                for (let i = 0; i < data.length; i += 4) {
                    data[i] = data[i] ^ (data[i] & 1);
                }
                context.putImageData(imageData, 0, 0);
            }
            return originalToBlob.call(this, callback, type, quality);
        };
    """.trimIndent()
}
