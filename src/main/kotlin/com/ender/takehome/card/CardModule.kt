package com.ender.takehome.card

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Card
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CardModule(
    private val dataAccess: CardDataAccess,
    private val tenantDataAccess: TenantDataAccess,
    private val stripeService: StripeService,
) {
    private val log = LoggerFactory.getLogger(CardModule::class.java)

    /**
     * Creates a Stripe-hosted Checkout Session for card collection. The Stripe
     * Customer is created lazily on first call and persisted on the tenant.
     */
    @Transactional
    fun createSetupCheckoutSession(tenantId: Long): String {
        val tenant = tenantDataAccess.findById(tenantId)
            ?: throw ResourceNotFoundException("Tenant not found: $tenantId")
        val customerId = tenant.stripeCustomerId
            ?: stripeService.createCustomer(tenant).also {
                tenantDataAccess.save(tenant.copy(stripeCustomerId = it))
            }
        return stripeService.createSetupCheckoutSession(customerId)
    }

    fun getCards(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<Card> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun deleteCard(tenantId: Long, cardId: Long) {
        val card = dataAccess.findById(cardId)
        if (card == null || card.tenantId != tenantId) {
            throw ResourceNotFoundException("Card not found: $cardId")
        }
        stripeService.detachPaymentMethod(card.stripePaymentMethodId)
        dataAccess.delete(cardId)
    }

    /**
     * Persists a local mirror of a card after Stripe confirms a SetupIntent
     * (setup_intent.succeeded webhook). Idempotent — duplicate deliveries and
     * cards already persisted are no-ops.
     */
    @Transactional
    fun persistCardFromSetupIntent(customerId: String, paymentMethodId: String) {
        if (dataAccess.findByStripePaymentMethodId(paymentMethodId) != null) return

        val tenant = tenantDataAccess.findByStripeCustomerId(customerId)
        if (tenant == null) {
            log.warn("setup_intent.succeeded for unknown Stripe customer: {}", customerId)
            return
        }
        val details = stripeService.getCardDetails(paymentMethodId)
        dataAccess.save(
            Card(
                tenantId = tenant.id,
                stripePaymentMethodId = paymentMethodId,
                brand = details.brand,
                last4 = details.last4,
                expMonth = details.expMonth,
                expYear = details.expYear,
            )
        )
    }
}
