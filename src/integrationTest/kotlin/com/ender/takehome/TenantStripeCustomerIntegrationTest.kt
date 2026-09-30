package com.ender.takehome

import com.ender.takehome.tenant.TenantDataAccess
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

@Tag("integration")
class TenantStripeCustomerIntegrationTest {

    @Test
    fun `persists Stripe customer id on tenant`() {
        val url = "jdbc:h2:mem:tenant-stripe-customer;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val tenantDataAccess = TenantDataAccess(DSL.using(connection, SQLDialect.H2))
            val tenant = requireNotNull(tenantDataAccess.findById(1L))
            val stripeCustomerId = "cus_integration_test"

            tenantDataAccess.save(tenant.copy(stripeCustomerId = stripeCustomerId))

            assertEquals(stripeCustomerId, tenantDataAccess.findById(tenant.id)?.stripeCustomerId)
        }
    }
}
