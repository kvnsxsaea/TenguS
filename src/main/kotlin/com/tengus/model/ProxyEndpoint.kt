package com.tengus.model

/**
 * A proxy endpoint used for outbound browser connections.
 *
 * Validates: Requirements 10.1
 */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val protocol: String = "http"
)
