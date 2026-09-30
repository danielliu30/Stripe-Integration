package com.ender.takehome

import com.ender.takehome.card.CardDataAccess
import com.ender.takehome.model.Card
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

@Tag("integration")
class CardDataAccessIntegrationTest {

    @Test
    fun `saves and finds card`() {
        val url = "jdbc:h2:mem:card-data-access;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dataAccess = CardDataAccess(DSL.using(connection, SQLDialect.H2))
            val card = Card(
                tenantId = 1L,
                stripePaymentMethodId = "pm_integration_test",
                brand = "visa",
                last4 = "4242",
                expMonth = 12,
                expYear = 2030,
            )

            val saved = dataAccess.save(card)

            assertEquals(saved, dataAccess.findById(saved.id))
        }
    }
}
