package com.ender.takehome.card

import com.ender.takehome.generated.tables.Cards.CARDS
import com.ender.takehome.generated.tables.records.CardsRecord
import com.ender.takehome.model.Card
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class CardDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): Card? =
        dsl.selectFrom(CARDS)
            .where(CARDS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun save(card: Card): Card {
        val record = dsl.newRecord(CARDS).apply {
            tenantId = card.tenantId
            stripePaymentMethodId = card.stripePaymentMethodId
            brand = card.brand
            last4 = card.last4
            expMonth = card.expMonth
            expYear = card.expYear
        }
        record.store()
        val id = requireNotNull(record.id)
        return requireNotNull(findById(id))
    }

    private fun CardsRecord.toModel() = Card(
        id = id!!,
        tenantId = tenantId!!,
        stripePaymentMethodId = stripePaymentMethodId!!,
        brand = brand!!,
        last4 = last4!!,
        expMonth = expMonth!!,
        expYear = expYear!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
