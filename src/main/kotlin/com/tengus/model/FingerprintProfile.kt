package com.tengus.model

/**
 * A complete browser fingerprint profile for stealth randomization.
 * All attributes are internally consistent with the selected user-agent.
 *
 * Validates: Requirements 6.1, 6.2, 6.3
 */
data class FingerprintProfile(
    val viewport: ViewportSize,
    val timezone: String,
    val language: String,
    val platform: String,
    val userAgent: String,
    val webglVendor: String,
    val webglRenderer: String
)
