package com.tengus.model

/**
 * Result of validating a normalized scrape result against the common schema.
 *
 * Validates: Requirements 14.2
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList()
)
