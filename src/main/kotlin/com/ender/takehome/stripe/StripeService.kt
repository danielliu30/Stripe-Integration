package com.ender.takehome.stripe

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import com.stripe.StripeClient
import com.stripe.model.SetupIntent
import com.stripe.net.RequestOptions
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.checkout.SessionCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

data class StripeCardDetails(
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
)

data class SetupIntentSucceeded(
    val customerId: String,
    val paymentMethodId: String,
)

data class StripeChargeResult(
    val paymentIntentId: String,
    val status: PaymentStatus,
    val failureReason: String?,
)

/**
 * Boundary for the Stripe operations used by card setup.
 * Keeping Stripe SDK types here prevents them from leaking into application logic.
 */
interface StripeService {
    /** Creates the Stripe Customer that owns a tenant's saved payment methods. */
    fun createCustomer(tenant: Tenant): String

    /** Creates a hosted setup session and returns the URL where the client sends the tenant. */
    fun createSetupCheckoutSession(customerId: String): String

    /** Verifies a Stripe webhook and returns the supported setup event, if present. */
    fun parseSetupIntentSucceeded(payload: String, signature: String): SetupIntentSucceeded?

    /** Retrieves the non-sensitive display fields for a Stripe card PaymentMethod. */
    fun getCardDetails(paymentMethodId: String): StripeCardDetails

    /** Detaches a saved PaymentMethod so it can no longer be charged for the tenant. */
    fun detachPaymentMethod(paymentMethodId: String)
}

/** Stripe boundary used only for charging previously saved payment methods. */
interface StripePaymentService {
    /**
     * Creates and confirms an off-session card PaymentIntent.
     *
     * [idempotencyKey] must be stable for the persisted local payment. Stripe returns the
     * original PaymentIntent when the same key and parameters are retried, preventing a second
     * external charge even if the first response was lost.
     *
     * [metadata] links the Stripe object back to local payment and rent-charge records for
     * support, reconciliation, and later webhook processing.
     */
    fun chargeCard(
        customerId: String,
        paymentMethodId: String,
        amount: BigDecimal,
        idempotencyKey: String,
        metadata: Map<String, String>,
    ): StripeChargeResult
}

@Component
class StripeServiceImpl(
    @Value("\${stripe.secret-key}") secretKey: String,
    @Value("\${stripe.webhook-secret}") private val webhookSecret: String,
    @Value("\${stripe.currency}") private val currency: String,
    @Value("\${stripe.checkout-success-url}") private val successUrl: String,
    @Value("\${stripe.checkout-cancel-url}") private val cancelUrl: String,
) : StripeService, StripePaymentService {

    private val client = StripeClient(secretKey)

    override fun createCustomer(tenant: Tenant): String =
        client.v1().customers().create(
            CustomerCreateParams.builder()
                .setEmail(tenant.email)
                .setName("${tenant.firstName} ${tenant.lastName}")
                .putMetadata("tenantId", tenant.id.toString())
                .build()
        ).id

    override fun createSetupCheckoutSession(customerId: String): String =
        client.v1().checkout().sessions().create(
            SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SETUP)
                .setCustomer(customerId)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .build()
        ).url

    override fun parseSetupIntentSucceeded(payload: String, signature: String): SetupIntentSucceeded? {
        val event = Webhook.constructEvent(payload, signature, webhookSecret)
        if (event.type != "setup_intent.succeeded") return null
        val setupIntent = event.dataObjectDeserializer.`object`.orElse(null) as? SetupIntent ?: return null
        val customerId = setupIntent.customer ?: return null
        val paymentMethodId = setupIntent.paymentMethod ?: return null
        return SetupIntentSucceeded(customerId, paymentMethodId)
    }

    override fun getCardDetails(paymentMethodId: String): StripeCardDetails {
        val card = client.v1().paymentMethods().retrieve(paymentMethodId).card
            ?: throw IllegalArgumentException("Payment method is not a card")
        return StripeCardDetails(card.brand, card.last4, card.expMonth.toInt(), card.expYear.toInt())
    }

    override fun detachPaymentMethod(paymentMethodId: String) {
        client.v1().paymentMethods().detach(paymentMethodId)
    }

    override fun chargeCard(
        customerId: String,
        paymentMethodId: String,
        amount: BigDecimal,
        idempotencyKey: String,
        metadata: Map<String, String>,
    ): StripeChargeResult {
        val builder = PaymentIntentCreateParams.builder()
            .setAmount(amount.movePointRight(2).longValueExact())
            .setCurrency(currency)
            .setCustomer(customerId)
            .setPaymentMethod(paymentMethodId)
            .setReturnUrl(successUrl)
            .setOffSession(true)
            .addPaymentMethodType("card")
            .setConfirm(true)
        metadata.forEach { (key, value) -> builder.putMetadata(key, value) }
        val params = builder.build()
        val options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()
        val paymentIntent = client.v1().paymentIntents().create(params, options)
        val status = when (paymentIntent.status) {
            "succeeded" -> PaymentStatus.SUCCEEDED
            "processing" -> PaymentStatus.PROCESSING
            "requires_action" -> PaymentStatus.REQUIRES_ACTION
            else -> PaymentStatus.FAILED
        }
        return StripeChargeResult(paymentIntent.id, status, paymentIntent.lastPaymentError?.message)
    }
}
