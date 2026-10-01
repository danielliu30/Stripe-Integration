package com.ender.takehome.card

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Card
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.tenant.TenantDataAccess
import org.springframework.stereotype.Service

@Service
class CardModule(
    private val dataAccess: CardDataAccess,
    private val tenantDataAccess: TenantDataAccess,
    private val stripeService: StripeService,
) {

    fun getByTenantId(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<Card> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val cards = dataAccess.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(cards, sanitized)
    }

    /**
     * Starts hosted card setup, creating the tenant's Stripe Customer only when needed.
     * Card persistence happens later from Stripe's signed setup-intent webhook.
     */
    fun createSetupCheckoutSession(tenantId: Long): String {
        val tenant = tenantDataAccess.findById(tenantId)
            ?: throw ResourceNotFoundException("Tenant not found: $tenantId")
        val customerId = tenant.stripeCustomerId ?: stripeService.createCustomer(tenant).also {
            tenantDataAccess.save(tenant.copy(stripeCustomerId = it))
        }
        return stripeService.createSetupCheckoutSession(customerId)
    }

    /**
     * Detaches a tenant-owned card and hides it from future use without deleting payment history.
     * Repeating a completed deletion is a no-op, avoiding a second Stripe detach call.
     */
    fun delete(tenantId: Long, cardId: Long) {
        val card = dataAccess.findById(cardId)
        if (card == null || card.tenantId != tenantId) {
            throw ResourceNotFoundException("Card not found: $cardId")
        }
        if (card.deletedAt != null) return
        stripeService.detachPaymentMethod(card.stripePaymentMethodId)
        dataAccess.markDeleted(card.id)
    }

    /** Persists safe card display data from an authenticated Stripe setup event. */
    fun persistCardFromSetupIntent(customerId: String, paymentMethodId: String) {
        if (dataAccess.findByStripePaymentMethodId(paymentMethodId) != null) return
        val tenant = tenantDataAccess.findByStripeCustomerId(customerId) ?: return
        val card = stripeService.getCardDetails(paymentMethodId)
        dataAccess.save(
            Card(
                tenantId = tenant.id,
                stripePaymentMethodId = paymentMethodId,
                brand = card.brand,
                last4 = card.last4,
                expMonth = card.expMonth,
                expYear = card.expYear,
            )
        )
    }
}
