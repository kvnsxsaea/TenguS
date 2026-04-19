package com.tengus.model

/**
 * Signals that indicate a proxy has been detected or blocked by a target site.
 *
 * Validates: Requirements 10.1
 */
enum class BlockingSignal {
    HTTP_403,
    CAPTCHA_CHALLENGE,
    CONNECTION_RESET
}
