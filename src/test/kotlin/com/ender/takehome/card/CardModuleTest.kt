package com.ender.takehome.card

import com.ender.takehome.TestFixtures
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.stripe.StripeCardDetails
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.stripe.StripeSetupIntentResult
import com.ender.takehome.tenant.TenantDataAccess
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardModuleTest {

    private val dataAccess = mockk<CardDataAccess>()
    private val tenantDataAccess = mockk<TenantDataAccess>()
    private val stripeService = mockk<StripeService>()
    private val module = CardModule(dataAccess, tenantDataAccess, stripeService)

    private val tenant = TestFixtures.tenant(id = 1)

    @BeforeEach
    fun setUp() {
        clearMocks(dataAccess, tenantDataAccess, stripeService)
    }

    @Test
    fun `createSetupIntent creates Stripe customer lazily and persists it`() {
        every { tenantDataAccess.findById(1L) } returns tenant
        every { stripeService.createCustomer(tenant) } returns "cus_new"
        every { tenantDataAccess.save(match { it.stripeCustomerId == "cus_new" }) } answers { firstArg() }
        every { stripeService.createSetupIntent("cus_new") } returns
            StripeSetupIntentResult("seti_1", "seti_1_secret_abc")

        val secret = module.createSetupIntent(1L)

        assertEquals("seti_1_secret_abc", secret)
        verify(exactly = 1) { stripeService.createCustomer(tenant) }
        verify(exactly = 1) { tenantDataAccess.save(match { it.stripeCustomerId == "cus_new" }) }
    }

    @Test
    fun `createSetupIntent reuses existing Stripe customer`() {
        val withCustomer = tenant.copy(stripeCustomerId = "cus_existing")
        every { tenantDataAccess.findById(1L) } returns withCustomer
        every { stripeService.createSetupIntent("cus_existing") } returns
            StripeSetupIntentResult("seti_2", "seti_2_secret_def")

        val secret = module.createSetupIntent(1L)

        assertEquals("seti_2_secret_def", secret)
        verify(exactly = 0) { stripeService.createCustomer(any()) }
    }

    @Test
    fun `deleteCard detaches from Stripe and removes row when owned`() {
        val card = TestFixtures.card(id = 5, tenantId = 1)
        every { dataAccess.findById(5L) } returns card
        every { stripeService.detachPaymentMethod(card.stripePaymentMethodId) } just Runs
        every { dataAccess.delete(5L) } just Runs

        module.deleteCard(1L, 5L)

        verify(exactly = 1) { stripeService.detachPaymentMethod(card.stripePaymentMethodId) }
        verify(exactly = 1) { dataAccess.delete(5L) }
    }

    @Test
    fun `deleteCard throws 404 for another tenant's card without detaching`() {
        every { dataAccess.findById(5L) } returns TestFixtures.card(id = 5, tenantId = 99)

        assertThrows<ResourceNotFoundException> { module.deleteCard(1L, 5L) }
        verify(exactly = 0) { stripeService.detachPaymentMethod(any()) }
        verify(exactly = 0) { dataAccess.delete(any()) }
    }

    @Test
    fun `persistCardFromSetupIntent saves card for known customer`() {
        every { dataAccess.findByStripePaymentMethodId("pm_1") } returns null
        every { tenantDataAccess.findByStripeCustomerId("cus_1") } returns tenant
        every { stripeService.getCardDetails("pm_1") } returns StripeCardDetails("visa", "4242", 12, 2028)
        every { dataAccess.save(any()) } answers { firstArg() }

        module.persistCardFromSetupIntent("cus_1", "pm_1")

        verify(exactly = 1) {
            dataAccess.save(match { it.tenantId == 1L && it.last4 == "4242" && it.brand == "visa" })
        }
    }

    @Test
    fun `persistCardFromSetupIntent is idempotent on duplicate delivery`() {
        every { dataAccess.findByStripePaymentMethodId("pm_1") } returns TestFixtures.card()

        module.persistCardFromSetupIntent("cus_1", "pm_1")

        verify(exactly = 0) { stripeService.getCardDetails(any()) }
        verify(exactly = 0) { dataAccess.save(any()) }
    }
}
