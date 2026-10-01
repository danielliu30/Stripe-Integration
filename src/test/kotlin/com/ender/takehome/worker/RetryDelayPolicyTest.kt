package com.ender.takehome.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.random.Random

class RetryDelayPolicyTest {

    @Test
    fun `doubles delay from one based receive count`() {
        val policy = RetryDelayPolicy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.0)

        val delays = (1..5).map { policy.delay(it) }

        assertEquals(
            listOf(30L, 60L, 120L, 240L, 300L).map { Duration.ofSeconds(it) },
            delays,
        )
    }

    @Test
    fun `caps delay for arbitrarily high receive count`() {
        val policy = RetryDelayPolicy(Duration.ofSeconds(30), Duration.ofMinutes(5), 0.0)

        assertEquals(Duration.ofMinutes(5), policy.delay(Int.MAX_VALUE))
    }

    @Test
    fun `keeps jitter within configured bounds and maximum`() {
        val policy = RetryDelayPolicy(
            Duration.ofSeconds(100),
            Duration.ofSeconds(300),
            0.2,
            Random(1),
        )

        val delays = (1..100).map { policy.delay(1).seconds }

        assertTrue(delays.all { it in 80L..120L })
        assertTrue(delays.distinct().size > 1)
        assertTrue((1..100).map { policy.delay(4) }.all { it <= Duration.ofSeconds(300) })
    }

    @Test
    fun `rejects invalid policy configuration and receive count`() {
        assertThrows<IllegalArgumentException> {
            RetryDelayPolicy(Duration.ZERO, Duration.ofSeconds(30), 0.0)
        }
        assertThrows<IllegalArgumentException> {
            RetryDelayPolicy(Duration.ofSeconds(30), Duration.ofSeconds(20), 0.0)
        }
        assertThrows<IllegalArgumentException> {
            RetryDelayPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60), 1.1)
        }
        val policy = RetryDelayPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60), 0.0)
        assertThrows<IllegalArgumentException> { policy.delay(0) }
    }
}
