package com.deviante.repository

import com.deviante.db.BusinessesTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class BusinessRepository {
    fun idByName(name: String): UUID? = transaction {
        BusinessesTable.selectAll()
            .map { it[BusinessesTable.id] to it[BusinessesTable.name] }
            .firstOrNull { (_, n) -> n.equals(name.trim(), ignoreCase = true) }
            ?.first
    }

    fun namesById(): Map<UUID, String> = transaction {
        BusinessesTable.selectAll().associate { it[BusinessesTable.id] to it[BusinessesTable.name] }
    }
}
