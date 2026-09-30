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
}
