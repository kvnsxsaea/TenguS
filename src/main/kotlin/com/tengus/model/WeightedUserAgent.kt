package com.tengus.model

/**
 * A user-agent string with a selection weight and browser metadata.
 *
 * Validates: Requirements 9.1
 */
data class WeightedUserAgent(
    val userAgent: String,
    val weight: Double,
    val browserFamily: String,
    val browserVersion: String
)
