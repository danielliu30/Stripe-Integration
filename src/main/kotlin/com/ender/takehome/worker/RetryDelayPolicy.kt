package com.ender.takehome.worker

import java.time.Duration
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Calculates a retry delay from SQS's one-based receive count.
 *
 * The first failed delivery waits [baseDelay], each subsequent delivery doubles that delay up to
 * [maxDelay], and [jitterRatio] spreads workers within a bounded range to avoid synchronized retry
 * spikes. The result never exceeds [maxDelay] and is deterministic when jitter is disabled.
 */
class RetryDelayPolicy(
    private val baseDelay: Duration,
    private val maxDelay: Duration,
    private val jitterRatio: Double,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelay >= Duration.ofSeconds(1)) { "Base retry delay must be at least one second" }
        require(maxDelay >= baseDelay) { "Maximum retry delay must not be less than the base delay" }
        require(jitterRatio in 0.0..1.0) { "Jitter ratio must be between 0 and 1" }
    }

    fun delay(receiveCount: Int): Duration {
        require(receiveCount >= 1) { "SQS receive count must be at least 1" }
        var delaySeconds = baseDelay.seconds
        repeat(minOf(receiveCount - 1, 62)) {
            delaySeconds = if (delaySeconds > maxDelay.seconds - delaySeconds) {
                maxDelay.seconds
            } else delaySeconds * 2
        }
        if (jitterRatio == 0.0) return Duration.ofSeconds(delaySeconds)
        val jittered = (delaySeconds * (1 + random.nextDouble(-jitterRatio, jitterRatio))).roundToLong()
        return Duration.ofSeconds(jittered.coerceIn(1, maxDelay.seconds))
    }
}
