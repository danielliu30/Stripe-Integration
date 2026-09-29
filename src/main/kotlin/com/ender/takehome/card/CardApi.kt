package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.response.CardResponse
import com.ender.takehome.dto.response.CheckoutSessionResponse
import com.ender.takehome.dto.response.CursorPage
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasRole('TENANT')")
class CardApi(private val cardModule: CardModule) {

    @PostMapping("/checkout-session")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCheckoutSession(): CheckoutSessionResponse =
        CheckoutSessionResponse(cardModule.createSetupCheckoutSession(UserPrincipal.current().tenantId!!))

    @GetMapping
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<CardResponse> {
        val page = cardModule.getCards(UserPrincipal.current().tenantId!!, startAfterId, limit)
        return CursorPage(page.content.map { CardResponse.from(it) }, page.hasMore)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) =
        cardModule.deleteCard(UserPrincipal.current().tenantId!!, id)
}
