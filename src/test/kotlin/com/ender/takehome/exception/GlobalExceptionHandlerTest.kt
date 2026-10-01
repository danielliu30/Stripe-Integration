package com.ender.takehome.exception

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingRequestHeaderException

class GlobalExceptionHandlerTest {

    @Test
    fun `missing request header returns structured bad request`() {
        val response = GlobalExceptionHandler().handleMissingHeader(
            MissingRequestHeaderException("Idempotency-Key", mockk<MethodParameter>())
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(400, response.body?.status)
        assertEquals("Bad Request", response.body?.error)
        assertEquals("Missing required header: Idempotency-Key", response.body?.message)
    }
}
