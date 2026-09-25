package com.ender.takehome.stripe

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import com.stripe.StripeClient
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
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

data class StripeChargeResult(
    val paymentIntentId: String,
    val status: PaymentStatus,
    val clientSecret: String?,
    val failureReason: String?,
)

/**
 * Thin wrapper over stripe-java so the rest of the app never touches Stripe
 * types (and so module tests can mock the boundary with MockK).
 */
interface StripeService {
    fun createCustomer(tenant: Tenant): String
    fun createSetupCheckoutSession(customerId: String): String
    fun getCardDetails(paymentMethodId: String): StripeCardDetails
    fun detachPaymentMethod(paymentMethodId: String)
    fun chargeCard(
        customerId: String,
        paymentMethodId: String,
        amount: BigDecimal,
        idempotencyKey: String,
        metadata: Map<String, String>,
    ): StripeChargeResult
    fun constructEvent(payload: String, signature: String): Event
}

@Component
class StripeServiceImpl(
    @Value("\${stripe.secret-key}") secretKey: String,
    @Value("\${stripe.webhook-secret}") private val webhookSecret: String,
    @Value("\${stripe.currency}") private val currency: String,
    @Value("\${stripe.checkout-success-url}") private val checkoutSuccessUrl: String,
    @Value("\${stripe.checkout-cancel-url}") private val checkoutCancelUrl: String,
) : StripeService {

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
                .setSuccessUrl(checkoutSuccessUrl)
                .setCancelUrl(checkoutCancelUrl)
                .build()
        ).url

    override fun getCardDetails(paymentMethodId: String): StripeCardDetails {
        val pm = client.v1().paymentMethods().retrieve(paymentMethodId)
        val card = pm.card
            ?: throw IllegalStateException("Payment method is not a card: $paymentMethodId")
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
        val params = PaymentIntentCreateParams.builder()
            // Stripe amounts are in the smallest currency unit (cents for USD)
            .setAmount(amount.movePointRight(2).longValueExact())
            .setCurrency(currency)
            .setCustomer(customerId)
            .setPaymentMethod(paymentMethodId)
            // Saved-card off_session charges only — restricting to "card" disables
            // automatic_payment_methods, which would require a return_url for
            // redirect-based methods we never support here.
            .addPaymentMethodType("card")
            .setConfirm(true)
            .apply { metadata.forEach { (k, v) -> putMetadata(k, v) } }
            .build()
        val options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()
        val pi = client.v1().paymentIntents().create(params, options)
        return StripeChargeResult(
            paymentIntentId = pi.id,
            status = mapStatus(pi),
            clientSecret = pi.clientSecret,
            failureReason = pi.lastPaymentError?.message,
        )
    }

    override fun constructEvent(payload: String, signature: String): Event =
        Webhook.constructEvent(payload, signature, webhookSecret)

    private fun mapStatus(pi: PaymentIntent): PaymentStatus = when (pi.status) {
        "requires_payment_method", "requires_confirmation" -> PaymentStatus.INITIATED
        "requires_action" -> PaymentStatus.REQUIRES_ACTION
        "processing" -> PaymentStatus.PROCESSING
        "succeeded" -> PaymentStatus.SUCCEEDED
        else -> PaymentStatus.FAILED
    }
}
