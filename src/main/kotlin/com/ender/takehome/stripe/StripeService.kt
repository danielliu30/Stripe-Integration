package com.ender.takehome.stripe

import com.ender.takehome.model.Tenant
import com.stripe.StripeClient
import com.stripe.param.CustomerCreateParams
import com.stripe.param.checkout.SessionCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Boundary for the Stripe operations used by card setup.
 * Keeping Stripe SDK types here prevents them from leaking into application logic.
 */
interface StripeService {
    /** Creates the Stripe Customer that owns a tenant's saved payment methods. */
    fun createCustomer(tenant: Tenant): String

    /** Creates a hosted setup session and returns the URL where the client sends the tenant. */
    fun createSetupCheckoutSession(customerId: String): String
}

@Component
class StripeServiceImpl(
    @Value("\${stripe.secret-key}") secretKey: String,
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
}
