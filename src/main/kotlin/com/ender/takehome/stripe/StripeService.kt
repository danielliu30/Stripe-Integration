package com.ender.takehome.stripe

import com.ender.takehome.model.Tenant
import com.stripe.StripeClient
import com.stripe.model.SetupIntent
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.checkout.SessionCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

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

@Component
class StripeServiceImpl(
    @Value("\${stripe.secret-key}") secretKey: String,
    @Value("\${stripe.webhook-secret}") private val webhookSecret: String,
    @Value("\${stripe.checkout-success-url}") private val successUrl: String,
    @Value("\${stripe.checkout-cancel-url}") private val cancelUrl: String,
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
}
