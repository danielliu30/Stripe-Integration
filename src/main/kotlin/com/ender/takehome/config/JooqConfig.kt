package com.ender.takehome.config

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class JooqConfig {

    @Bean
    fun dslContext(
        dataSource: DataSource,
        @Value("\${spring.jooq.sql-dialect:MYSQL}") dialect: String,
    ): DSLContext {
        val config = DefaultConfiguration()
            .set(dataSource)
            .set(SQLDialect.valueOf(dialect))
        return DSL.using(config)
    }
}
