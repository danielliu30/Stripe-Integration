package com.ender.takehome.webhook

import com.ender.takehome.card.CardModule
import com.ender.takehome.ledger.LedgerModule
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.stripe.StripeService
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Charge
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.model.SetupIntent
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class StripeWebhookApi(
    private val stripeService: StripeService,
    private val cardModule: CardModule,
    private val ledgerModule: LedgerModule,
) {
    private val log = LoggerFactory.getLogger(StripeWebhookApi::class.java)

    @PostMapping("/api/webhooks/stripe")
    fun handle(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        if (signature.isNullOrBlank()) {
            throw IllegalArgumentException("Missing Stripe-Signature header")
        }
        val event = try {
            stripeService.constructEvent(payload, signature)
        } catch (e: SignatureVerificationException) {
            throw IllegalArgumentException("Invalid webhook signature")
        }

        when (event.type) {
            "setup_intent.succeeded" -> event.dataObject<SetupIntent>()?.let { si ->
                if (si.customer != null && si.paymentMethod != null) {
                    cardModule.persistCardFromSetupIntent(si.customer, si.paymentMethod)
                }
            }
            "payment_intent.succeeded" ->
                event.dataObject<PaymentIntent>()?.let { ledgerModule.applyStripeEvent(it.id, PaymentStatus.SUCCEEDED) }
            "payment_intent.processing" ->
                event.dataObject<PaymentIntent>()?.let { ledgerModule.applyStripeEvent(it.id, PaymentStatus.PROCESSING) }
            "payment_intent.payment_failed" ->
                event.dataObject<PaymentIntent>()?.let {
                    ledgerModule.applyStripeEvent(it.id, PaymentStatus.FAILED, it.lastPaymentError?.message ?: "payment failed")
                }
            "payment_intent.canceled" ->
                event.dataObject<PaymentIntent>()?.let { ledgerModule.applyStripeEvent(it.id, PaymentStatus.FAILED, "canceled") }
            "charge.refunded" ->
                event.dataObject<Charge>()?.paymentIntent?.let { ledgerModule.applyStripeEvent(it, PaymentStatus.REFUNDED) }
            else -> log.debug("Ignoring unhandled Stripe event type: {}", event.type)
        }
        return ResponseEntity.ok().build()
    }

    private inline fun <reified T> Event.dataObject(): T? =
        dataObjectDeserializer.`object`.orElse(null) as? T
}
