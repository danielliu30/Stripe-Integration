package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.model.Card
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager

@Tag("integration")
class CardDataAccessIntegrationTest {

    @Test
    fun `saves finds and lists cards by tenant`() {
        val url = "jdbc:h2:mem:card-data-access;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val dataAccess = CardDataAccess(dsl)
            val card = Card(
                tenantId = 1L,
                stripePaymentMethodId = "pm_integration_test",
                brand = "visa",
                last4 = "4242",
                expMonth = 12,
                expYear = 2030,
            )

            val saved = dataAccess.save(card)
            dataAccess.save(card.copy(tenantId = 2L, stripePaymentMethodId = "pm_other_tenant"))

            assertEquals(saved, dataAccess.findById(saved.id))
            assertEquals(listOf(saved), dataAccess.findByTenantIdCursor(1L, null, 20))

            LedgerDataAccess(dsl).savePayment(
                Payment(
                    rentChargeId = 1L,
                    amount = BigDecimal("1.00"),
                    paymentMethod = PaymentMethod.CREDIT_CARD,
                    cardId = saved.id,
                    recordedBy = "integration@test.com",
                )
            )
            dataAccess.markDeleted(saved.id)

            assertEquals(saved.id, dataAccess.findById(saved.id)?.id)
            assertEquals(null, dataAccess.findActiveById(saved.id))
            assertEquals(emptyList<Card>(), dataAccess.findByTenantIdCursor(1L, null, 20))
        }
    }
}
