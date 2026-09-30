package com.ender.takehome.card

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.Card
import org.springframework.stereotype.Service

@Service
class CardModule(private val dataAccess: CardDataAccess) {

    fun getByTenantId(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<Card> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val cards = dataAccess.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(cards, sanitized)
    }
}
