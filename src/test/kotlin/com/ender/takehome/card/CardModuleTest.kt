package com.ender.takehome.card

import com.ender.takehome.TestFixtures
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.exception.UpstreamException
import com.ender.takehome.model.Card
import com.ender.takehome.stripe.StripeCardDetails
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import com.stripe.exception.ApiConnectionException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CardModuleTest {

    private val cardDataAccess = mockk<CardDataAccess>()
    private val tenantDataAccess = mockk<TenantDataAccess>()
    private val stripeService = mockk<StripeService>()
    private val module = CardModule(cardDataAccess, tenantDataAccess, stripeService)

    @Test
    fun `creates Stripe customer before first checkout session`() {
        val tenant = TestFixtures.tenant(id = 1L)
        every { tenantDataAccess.findById(1L) } returns tenant
        every { stripeService.createCustomer(tenant) } returns "cus_new"
        every { tenantDataAccess.save(any()) } answers { firstArg() }
        every { stripeService.createSetupCheckoutSession("cus_new") } returns "https://checkout.stripe.test/new"

        val url = module.createSetupCheckoutSession(1L)

        assertEquals("https://checkout.stripe.test/new", url)
        verify { tenantDataAccess.save(tenant.copy(stripeCustomerId = "cus_new")) }
    }

    @Test
    fun `reuses existing Stripe customer for checkout session`() {
        val tenant = TestFixtures.tenant(id = 1L).copy(stripeCustomerId = "cus_existing")
        every { tenantDataAccess.findById(1L) } returns tenant
        every { stripeService.createSetupCheckoutSession("cus_existing") } returns "https://checkout.stripe.test/existing"

        val url = module.createSetupCheckoutSession(1L)

        assertEquals("https://checkout.stripe.test/existing", url)
        verify(exactly = 0) { stripeService.createCustomer(any()) }
        verify(exactly = 0) { tenantDataAccess.save(any()) }
    }

    @Test
    fun `customer failure becomes upstream error without persisting tenant`() {
        val tenant = TestFixtures.tenant(id = 1L)
        every { tenantDataAccess.findById(1L) } returns tenant
        every { stripeService.createCustomer(tenant) } throws ApiConnectionException("Stripe unavailable")

        assertThrows<UpstreamException> { module.createSetupCheckoutSession(1L) }

        verify(exactly = 0) { tenantDataAccess.save(any()) }
        verify(exactly = 0) { stripeService.createSetupCheckoutSession(any()) }
    }

    @Test
    fun `checkout failure becomes upstream error without changing existing customer`() {
        val tenant = TestFixtures.tenant(id = 1L).copy(stripeCustomerId = "cus_existing")
        every { tenantDataAccess.findById(1L) } returns tenant
        every {
            stripeService.createSetupCheckoutSession("cus_existing")
        } throws ApiConnectionException("Stripe unavailable")

        assertThrows<UpstreamException> { module.createSetupCheckoutSession(1L) }

        verify(exactly = 0) { tenantDataAccess.save(any()) }
    }

    @Test
    fun `detaches and deactivates tenant owned card`() {
        val card = Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030)
        every { cardDataAccess.findById(1L) } returns card
        every { stripeService.detachPaymentMethod("pm_test") } returns Unit
        every { cardDataAccess.markDeleted(1L) } returns Unit

        module.delete(1L, 1L)

        verify { stripeService.detachPaymentMethod("pm_test") }
        verify { cardDataAccess.markDeleted(1L) }
    }

    @Test
    fun `detach failure becomes upstream error without deactivating card`() {
        val card = Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030)
        every { cardDataAccess.findById(1L) } returns card
        every {
            stripeService.detachPaymentMethod("pm_test")
        } throws ApiConnectionException("Stripe unavailable")

        assertThrows<UpstreamException> { module.delete(1L, 1L) }

        verify(exactly = 0) { cardDataAccess.markDeleted(any()) }
    }

    @Test
    fun `hides card owned by another tenant`() {
        every { cardDataAccess.findById(1L) } returns Card(1L, 2L, "pm_test", "visa", "4242", 12, 2030)

        assertThrows<ResourceNotFoundException> { module.delete(1L, 1L) }
        verify(exactly = 0) { stripeService.detachPaymentMethod(any()) }
        verify(exactly = 0) { cardDataAccess.markDeleted(any()) }
    }

    @Test
    fun `repeated deletion does not detach card again`() {
        val card = Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030, deletedAt = Instant.now())
        every { cardDataAccess.findById(1L) } returns card

        module.delete(1L, 1L)

        verify(exactly = 0) { stripeService.detachPaymentMethod(any()) }
        verify(exactly = 0) { cardDataAccess.markDeleted(any()) }
    }

    @Test
    fun `persists card from setup intent`() {
        val tenant = TestFixtures.tenant(id = 1L).copy(stripeCustomerId = "cus_test")
        every { cardDataAccess.findByStripePaymentMethodId("pm_test") } returns null
        every { tenantDataAccess.findByStripeCustomerId("cus_test") } returns tenant
        every { stripeService.getCardDetails("pm_test") } returns StripeCardDetails("visa", "4242", 12, 2030)
        every { cardDataAccess.save(any()) } answers { firstArg() }

        module.persistCardFromSetupIntent("cus_test", "pm_test")

        verify {
            cardDataAccess.save(match {
                it.tenantId == tenant.id && it.stripePaymentMethodId == "pm_test" && it.last4 == "4242"
            })
        }
    }

    @Test
    fun `card detail failure becomes upstream error without persisting card`() {
        val tenant = TestFixtures.tenant(id = 1L).copy(stripeCustomerId = "cus_test")
        every { cardDataAccess.findByStripePaymentMethodId("pm_test") } returns null
        every { tenantDataAccess.findByStripeCustomerId("cus_test") } returns tenant
        every {
            stripeService.getCardDetails("pm_test")
        } throws ApiConnectionException("Stripe unavailable")

        assertThrows<UpstreamException> {
            module.persistCardFromSetupIntent("cus_test", "pm_test")
        }

        verify(exactly = 0) { cardDataAccess.save(any()) }
    }

    @Test
    fun `ignores duplicate setup intent`() {
        every { cardDataAccess.findByStripePaymentMethodId("pm_test") } returns
            Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030)

        module.persistCardFromSetupIntent("cus_test", "pm_test")

        verify(exactly = 0) { stripeService.getCardDetails(any()) }
        verify(exactly = 0) { cardDataAccess.save(any()) }
    }

    @Test
    fun `rejects checkout session for missing tenant`() {
        every { tenantDataAccess.findById(99L) } returns null

        assertThrows<ResourceNotFoundException> { module.createSetupCheckoutSession(99L) }
        verify(exactly = 0) { stripeService.createSetupCheckoutSession(any()) }
    }
}
