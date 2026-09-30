package com.ender.takehome.card

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** User-facing landing endpoint for Stripe Checkout success and cancellation redirects. */
@RestController
class CheckoutReturnApi {

    /**
     * Reports the browser redirect outcome only.
     * Signed Stripe webhooks remain the source of truth for card persistence.
     */
    @GetMapping("/api/checkout/return")
    fun handle(@RequestParam(defaultValue = "success") status: String): CheckoutReturnResponse =
        if (status == "cancelled") {
            CheckoutReturnResponse("cancelled", "Card setup was cancelled. No card was saved.")
        } else {
            CheckoutReturnResponse("success", "Card details submitted. Your card will appear after confirmation.")
        }
}

data class CheckoutReturnResponse(
    val status: String,
    val message: String,
)
