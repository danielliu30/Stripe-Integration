package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.Card
import com.ender.takehome.model.UserRole
import io.mockk.every
import io.mockk.mockk
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
