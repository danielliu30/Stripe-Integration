package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.response.CardResponse
import com.ender.takehome.dto.response.CheckoutSessionResponse
import com.ender.takehome.dto.response.CursorPage
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/cards")
class CardApi(private val cardModule: CardModule) {

    /** Removes a tenant-owned card locally after detaching it from Stripe. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('TENANT')")
    fun delete(@PathVariable id: Long) {
        val tenantId = requireNotNull(UserPrincipal.current().tenantId)
        cardModule.delete(tenantId, id)
    }

    /** Returns the Stripe-hosted page where the authenticated tenant enters card details. */
    @PostMapping("/checkout-session")
    @PreAuthorize("hasRole('TENANT')")
    fun createCheckoutSession(): CheckoutSessionResponse {
        val tenantId = requireNotNull(UserPrincipal.current().tenantId)
        return CheckoutSessionResponse(cardModule.createSetupCheckoutSession(tenantId))
    }

    @GetMapping
    @PreAuthorize("hasRole('TENANT')")
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<CardResponse> {
        val tenantId = requireNotNull(UserPrincipal.current().tenantId)
        val page = cardModule.getByTenantId(tenantId, startAfterId, limit)
        return CursorPage(page.content.map(CardResponse::from), page.hasMore)
    }
}
