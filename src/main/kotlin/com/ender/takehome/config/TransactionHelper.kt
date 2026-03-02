package com.ender.takehome.config

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Component
class TransactionHelper(
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(TransactionHelper::class.java)

    /**
     * Execute [block] in a transaction with the given [isolationLevel].
     * Retries up to [maxRetries] times on transient failures (deadlock, lock wait timeout).
     */
    fun <T> executeWithRetry(
        isolationLevel: Int = TransactionDefinition.ISOLATION_DEFAULT,
        maxRetries: Int = 3,
        block: () -> T,
    ): T {
        val template = TransactionTemplate(txManager).apply {
            this.isolationLevel = isolationLevel
        }
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return template.execute { block() }!!
            } catch (e: Exception) {
                if (isTransient(e)) {
                    lastException = e
                    log.warn("Transient failure on attempt ${attempt + 1}/$maxRetries, retrying", e)
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    private fun isTransient(e: Exception): Boolean {
        val message = generateSequence(e as Throwable) { it.cause }
            .mapNotNull { it.message?.lowercase() }
            .joinToString(" ")
        return message.contains("deadlock") || message.contains("lock wait timeout")
    }
}
