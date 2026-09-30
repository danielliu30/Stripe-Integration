package com.ender.takehome.webhook

import com.ender.takehome.card.CardModule
import com.ender.takehome.stripe.StripeService
import com.stripe.exception.SignatureVerificationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Receives signed Stripe events that advance server-side card setup state. */
@RestController
class StripeWebhookApi(
    private val stripeService: StripeService,
    private val cardModule: CardModule,
) {

    @PostMapping("/api/webhooks/stripe")
    fun handle(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        if (signature.isNullOrBlank()) throw IllegalArgumentException("Missing Stripe-Signature header")
        val event = try {
            stripeService.parseSetupIntentSucceeded(payload, signature)
        } catch (_: SignatureVerificationException) {
            throw IllegalArgumentException("Invalid webhook signature")
        }
        event?.let { cardModule.persistCardFromSetupIntent(it.customerId, it.paymentMethodId) }
        return ResponseEntity.ok().build()
    }
}
