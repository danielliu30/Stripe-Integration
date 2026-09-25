package com.ender.takehome.card

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Landing target for Stripe-hosted Checkout redirects (success_url / cancel_url).
 * Public endpoint — Stripe redirects the user's browser here without a JWT.
 */
@RestController
class CheckoutReturnApi {

    @GetMapping("/api/checkout/return")
    fun checkoutReturn(@RequestParam(defaultValue = "success") status: String): Map<String, String> =
        mapOf(
            "status" to when (status) {
                "cancelled" -> "cancelled"
                else -> "success"
            },
            "message" to when (status) {
                "cancelled" -> "Card setup was cancelled. No card was saved."
                else -> "Card saved successfully. You can close this page."
            },
        )
}
