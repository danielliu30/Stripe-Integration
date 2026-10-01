package com.ender.takehome

import com.ender.takehome.generated.tables.PaymentRecoveries.PAYMENT_RECOVERIES
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.sql.DriverManager

@Tag("integration")
class PaymentRecoverySchemaIntegrationTest {

    @Test
    fun `migration enforces payment recovery lifecycle contract`() {
        val url = "jdbc:h2:mem:payment-recovery-schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val paymentId = savePayment(dsl, "first")
            val record = dsl.newRecord(PAYMENT_RECOVERIES).apply { this.paymentId = paymentId }

            record.store()

            val saved = requireNotNull(
                dsl.selectFrom(PAYMENT_RECOVERIES)
                    .where(PAYMENT_RECOVERIES.PAYMENT_ID.eq(paymentId))
                    .fetchOne()
            )
            assertEquals("PENDING", saved.status)
            assertNotNull(saved.availableAt)
            assertNotNull(saved.createdAt)
            assertNotNull(saved.updatedAt)
            assertNull(saved.claimedAt)
            assertNull(saved.publishedAt)
            assertNull(saved.completedAt)
            assertThrows<IntegrityConstraintViolationException> {
                dsl.newRecord(PAYMENT_RECOVERIES).apply { this.paymentId = paymentId }.store()
            }

            val secondPaymentId = savePayment(dsl, "second")
            assertThrows<IntegrityConstraintViolationException> {
                dsl.newRecord(PAYMENT_RECOVERIES).apply {
                    this.paymentId = secondPaymentId
                    status = "UNKNOWN"
                }.store()
            }
            assertThrows<IntegrityConstraintViolationException> {
                dsl.newRecord(PAYMENT_RECOVERIES).apply { this.paymentId = Long.MAX_VALUE }.store()
            }
        }
    }

    private fun savePayment(dsl: DSLContext, suffix: String) = LedgerDataAccess(dsl).savePayment(
        Payment(
            rentChargeId = 1L,
            amount = BigDecimal("1.00"),
            paymentMethod = PaymentMethod.CHECK,
            idempotencyKey = "recovery-schema-$suffix",
            recordedBy = "integration@test.com",
        )
    ).id
}
