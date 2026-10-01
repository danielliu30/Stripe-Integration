package com.ender.takehome.stripe

import com.ender.takehome.model.PaymentStatus
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StripeServiceTest {

    private val webhookSecret = "whsec_unit_test"
    private val service = StripeServiceImpl(
        secretKey = "unit-test-placeholder",
        webhookSecret = webhookSecret,
        currency = "usd",
        successUrl = "http://localhost/success",
        cancelUrl = "http://localhost/cancel",
    )

    @Test
    fun `verifies and parses setup intent succeeded event`() {
        val payload = payload()

        val event = service.parseWebhookEvent(payload, signature(payload))

        assertEquals(SetupIntentSucceeded("cus_test", "pm_test"), event)
    }

    @Test
    fun `verifies and parses failed payment intent event`() {
        val payload = paymentPayload()

        val event = service.parseWebhookEvent(payload, signature(payload))

        assertEquals(
            StripePaymentUpdated("pi_test", PaymentStatus.FAILED, "Card declined"),
            event,
        )
    }

    @Test
    fun `translates only a fully refunded charge`() {
        val payload = refundPayload(refunded = true)

        val event = service.parseWebhookEvent(payload, signature(payload))

        assertEquals(StripePaymentUpdated("pi_test", PaymentStatus.REFUNDED), event)
    }

    @Test
    fun `ignores a partially refunded charge`() {
        val payload = refundPayload(refunded = false)

        val event = service.parseWebhookEvent(payload, signature(payload))

        assertEquals(UnhandledStripeWebhookEvent, event)
    }

    @Test
    fun `acknowledges signed event without API version as unhandled`() {
        val payload = payloadWithoutApiVersion()

        val event = service.parseWebhookEvent(payload, signature(payload))

        assertEquals(UnhandledStripeWebhookEvent, event)
    }

    @Test
    fun `rejects invalid webhook signature`() {
        assertThrows<SignatureVerificationException> {
            service.parseWebhookEvent(payload(), "t=1,v1=invalid")
        }
    }

    private fun payload() = """
        {
          "id": "evt_test",
          "object": "event",
          "api_version": "${Stripe.API_VERSION}",
          "type": "setup_intent.succeeded",
          "data": {
            "object": {
              "id": "seti_test",
              "object": "setup_intent",
              "customer": "cus_test",
              "payment_method": "pm_test",
              "status": "succeeded"
            }
          }
        }
    """.trimIndent()

    private fun payloadWithoutApiVersion() = """
        {
          "id": "evt_incomplete",
          "object": "event",
          "type": "payment_intent.succeeded",
          "data": {
            "object": {
              "id": "pi_incomplete",
              "object": "payment_intent",
              "status": "succeeded"
            }
          }
        }
    """.trimIndent()

    private fun paymentPayload() = """
        {
          "id": "evt_payment_test",
          "object": "event",
          "api_version": "${Stripe.API_VERSION}",
          "type": "payment_intent.payment_failed",
          "data": {
            "object": {
              "id": "pi_test",
              "object": "payment_intent",
              "status": "requires_payment_method",
              "last_payment_error": { "message": "Card declined" }
            }
          }
        }
    """.trimIndent()

    private fun refundPayload(refunded: Boolean) = """
        {
          "id": "evt_refund_test",
          "object": "event",
          "api_version": "${Stripe.API_VERSION}",
          "type": "charge.refunded",
          "data": {
            "object": {
              "id": "ch_test",
              "object": "charge",
              "payment_intent": "pi_test",
              "refunded": $refunded
            }
          }
        }
    """.trimIndent()

    private fun signature(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }
}
