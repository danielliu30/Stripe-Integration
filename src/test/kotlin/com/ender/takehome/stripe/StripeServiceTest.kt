package com.ender.takehome.stripe

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

        val event = service.parseSetupIntentSucceeded(payload, signature(payload))

        assertEquals(SetupIntentSucceeded("cus_test", "pm_test"), event)
    }

    @Test
    fun `rejects invalid webhook signature`() {
        assertThrows<SignatureVerificationException> {
            service.parseSetupIntentSucceeded(payload(), "t=1,v1=invalid")
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

    private fun signature(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }
}
