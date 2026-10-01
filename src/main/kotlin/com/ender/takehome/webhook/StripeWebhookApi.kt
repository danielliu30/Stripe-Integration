package com.ender.takehome.webhook

import com.ender.takehome.card.CardModule
import com.ender.takehome.ledger.LedgerModule
import com.ender.takehome.stripe.SetupIntentSucceeded
import com.ender.takehome.stripe.StripePaymentUpdated
import com.ender.takehome.stripe.StripeService
import com.ender.takehome.stripe.UnhandledStripeWebhookEvent
import com.stripe.exception.SignatureVerificationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Routes verified Stripe events to the card-setup or payment-lifecycle workflow.
 *
 * Valid unhandled and unknown-object events are acknowledged to stop Stripe retries. Missing or
 * invalid signatures are rejected before any state change. Lifecycle transition policy remains in
 * [LedgerModule], rather than in this transport adapter.
 */
@RestController
class StripeWebhookApi(
    private val stripeService: StripeService,
    private val cardModule: CardModule,
    private val ledgerModule: LedgerModule,
) {

    @PostMapping("/api/webhooks/stripe")
    fun handle(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        if (signature.isNullOrBlank()) throw IllegalArgumentException("Missing Stripe-Signature header")
        val event = try {
            stripeService.parseWebhookEvent(payload, signature)
        } catch (_: SignatureVerificationException) {
            throw IllegalArgumentException("Invalid webhook signature")
        }
        when (event) {
            is SetupIntentSucceeded ->
                cardModule.persistCardFromSetupIntent(event.customerId, event.paymentMethodId)
            is StripePaymentUpdated ->
                ledgerModule.applyStripePaymentEvent(event.paymentIntentId, event.status, event.failureReason)
            UnhandledStripeWebhookEvent -> Unit
        }
        return ResponseEntity.ok().build()
    }
}
