package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.Card
import com.ender.takehome.model.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class CardApiTest {

    private val cardModule = mockk<CardModule>()
    private val api = CardApi(cardModule)

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `deletes card for authenticated tenant`() {
        val principal = UserPrincipal(2L, "tenant@test.com", UserRole.TENANT, tenantId = 1L, pmId = null)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { cardModule.delete(1L, 5L) } returns Unit

        api.delete(5L)

        verify { cardModule.delete(1L, 5L) }
    }

    @Test
    fun `creates checkout session for authenticated tenant`() {
        val principal = UserPrincipal(2L, "tenant@test.com", UserRole.TENANT, tenantId = 1L, pmId = null)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { cardModule.createSetupCheckoutSession(1L) } returns "https://checkout.stripe.test/session"

        val result = api.createCheckoutSession()

        assertEquals("https://checkout.stripe.test/session", result.redirectUrl)
    }

    @Test
    fun `lists cards for authenticated tenant`() {
        val principal = UserPrincipal(2L, "tenant@test.com", UserRole.TENANT, tenantId = 1L, pmId = null)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        val card = Card(1L, 1L, "pm_test", "visa", "4242", 12, 2030)
        every { cardModule.getByTenantId(1L, null, 20) } returns CursorPage(listOf(card), false)

        val result = api.list(null, 20)

        assertEquals(1, result.content.size)
        assertEquals("4242", result.content.single().last4)
        assertEquals(false, result.hasMore)
    }
}
