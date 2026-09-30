package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.response.CardResponse
import com.ender.takehome.dto.response.CursorPage
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/cards")
class CardApi(private val cardModule: CardModule) {

    @GetMapping
    @PreAuthorize("hasRole('TENANT')")
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<CardResponse> {
        val tenantId = requireNotNull(UserPrincipal.current().tenantId)
        val page = cardModule.getByTenantId(tenantId, startAfterId, limit)
        return CursorPage(page.content.map(CardResponse::from), page.hasMore)
    }
}
