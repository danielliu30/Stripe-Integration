package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.Card
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager

@Tag("integration")
class PaymentLifecyclePersistenceIntegrationTest {

    @Test
    fun `persists credit card payment lifecycle fields`() {
        val url = "jdbc:h2:mem:payment-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val card = CardDataAccess(dsl).save(
                Card(
                    tenantId = 1L,
                    stripePaymentMethodId = "pm_test",
                    brand = "visa",
                    last4 = "4242",
                    expMonth = 12,
                    expYear = 2030,
                )
            )
            val dataAccess = LedgerDataAccess(dsl)
            val payment = Payment(
                rentChargeId = 1L,
                amount = BigDecimal("2500.00"),
                paymentMethod = PaymentMethod.CREDIT_CARD,
                status = PaymentStatus.PROCESSING,
                cardId = card.id,
                stripePaymentIntentId = "pi_test",
                idempotencyKey = "idem_test",
                recordedBy = "alice.johnson@email.com",
            )

            dataAccess.savePayment(payment)

            val saved = dataAccess.findPaymentsByRentChargeIdCursor(1L, null, 1).single()
            assertEquals(PaymentMethod.CREDIT_CARD, saved.paymentMethod)
            assertEquals(PaymentStatus.PROCESSING, saved.status)
            assertEquals(card.id, saved.cardId)
            assertEquals("pi_test", saved.stripePaymentIntentId)
            assertEquals("idem_test", saved.idempotencyKey)
        }
    }
}
