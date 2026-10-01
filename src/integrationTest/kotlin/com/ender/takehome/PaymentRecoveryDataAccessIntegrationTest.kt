package com.ender.takehome

import com.ender.takehome.ledger.LedgerDataAccess
import com.ender.takehome.ledger.PaymentRecoveryDataAccess
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentRecoveryStatus
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
class PaymentRecoveryDataAccessIntegrationTest {

    @Test
    fun `transitions recovery through claim publish and completion`() {
        val url = migratedDatabase("recovery-lifecycle")
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val paymentId = savePayment(dsl, "lifecycle")
            val dataAccess = PaymentRecoveryDataAccess(dsl)
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val created = dataAccess.create(paymentId, now.minusSeconds(1))

            val claimed = dataAccess.claimDue(now, 1).single()

            assertEquals(created.id, claimed.id)
            assertEquals(PaymentRecoveryStatus.PUBLISHING, claimed.status)
            assertEquals(now, claimed.claimedAt)
            assertTrue(dataAccess.markPublished(created.id, now.plusSeconds(1)))
            assertFalse(dataAccess.markPublished(created.id, now.plusSeconds(2)))
            assertTrue(dataAccess.markCompleted(paymentId, now.plusSeconds(3)))
            assertFalse(dataAccess.markCompleted(paymentId, now.plusSeconds(4)))
            val completed = requireNotNull(dataAccess.findByPaymentId(paymentId))
            assertEquals(PaymentRecoveryStatus.COMPLETED, completed.status)
            assertEquals(now.plusSeconds(3), completed.completedAt)
        }
    }

    @Test
    fun `releases failed and stale publisher claims`() {
        val url = migratedDatabase("recovery-release")
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val dataAccess = PaymentRecoveryDataAccess(dsl)
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val first = dataAccess.create(savePayment(dsl, "release"), now.minusSeconds(1))
            val second = dataAccess.create(savePayment(dsl, "stale"), now.minusSeconds(1))
            dataAccess.claimDue(now, 2)

            assertTrue(dataAccess.releaseClaim(first.id, now.plusSeconds(10), now.plusSeconds(1)))
            assertEquals(
                1,
                dataAccess.releaseStaleClaims(now, now.plusSeconds(20), now.plusSeconds(2)),
            )

            val released = requireNotNull(dataAccess.findById(first.id))
            val stale = requireNotNull(dataAccess.findById(second.id))
            assertEquals(PaymentRecoveryStatus.PENDING, released.status)
            assertEquals(now.plusSeconds(10), released.availableAt)
            assertNull(released.claimedAt)
            assertEquals(PaymentRecoveryStatus.PENDING, stale.status)
            assertEquals(now.plusSeconds(20), stale.availableAt)
            assertNull(stale.claimedAt)
        }
    }

    @Test
    fun `competing dispatchers never claim the same recovery`() {
        val url = migratedDatabase("recovery-contention")
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val dataAccess = PaymentRecoveryDataAccess(dsl)
            repeat(10) { index ->
                dataAccess.create(savePayment(dsl, "contention-$index"), now.minusSeconds(1))
            }
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims = (1..2).map {
                executor.submit<List<Long>> {
                    DriverManager.getConnection(url, "sa", "").use { connection ->
                        start.await()
                        PaymentRecoveryDataAccess(DSL.using(connection, SQLDialect.H2))
                            .claimDue(now, 10)
                            .map { recovery -> recovery.id }
                    }
                }
            }
            start.countDown()
            val first = claims[0].get()
            val second = claims[1].get()

            assertTrue(first.intersect(second.toSet()).isEmpty())
            assertEquals(10, (first + second).distinct().size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun migratedDatabase(name: String): String {
        val url = "jdbc:h2:mem:$name;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "").load().migrate()
        return url
    }

    private fun savePayment(dsl: DSLContext, suffix: String) = LedgerDataAccess(dsl).savePayment(
        Payment(
            rentChargeId = 1L,
            amount = BigDecimal("1.00"),
            paymentMethod = PaymentMethod.CHECK,
            idempotencyKey = "recovery-data-$suffix",
            recordedBy = "integration@test.com",
        )
    ).id
}
