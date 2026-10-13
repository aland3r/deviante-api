package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Empresa cliente: dona dos processos, análises, monitoramentos e máquinas. */
object BusinessesTable : Table(name = "businesses") {
    override val tableName = "deviante.businesses"

    val id = uuid("id")
    val name = varchar("name", 255).uniqueIndex()
    val cnpj = varchar("cnpj", 18).uniqueIndex()
    val sector = varchar("sector", 100)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
