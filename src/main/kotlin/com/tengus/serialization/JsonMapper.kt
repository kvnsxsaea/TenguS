package com.tengus.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Singleton ObjectMapper configured for the TenguS service.
 *
 * - JavaTimeModule for Instant serialization as ISO-8601 strings
 * - FAIL_ON_UNKNOWN_PROPERTIES = false for forward compatibility
 * - WRITE_DATES_AS_TIMESTAMPS = false
 * - Kotlin module registered via jacksonObjectMapper()
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 14.1, 14.3
 */
object JsonMapper {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }
}
