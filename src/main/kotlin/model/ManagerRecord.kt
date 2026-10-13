package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class ManagerRecord(
    val id: UUID,
    val userId: UUID,
    val email: String,
    val fullName: String,
    val role: String = "manager", // owner, admin, manager, operator, technician, mentor
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    fun isOwner(): Boolean = role == "owner"

    /** Excluir registros é permitido só ao administrador (owner ou admin) e ao gestor. */
    fun canDelete(): Boolean = role in DELETE_ROLES

    companion object {
        val DELETE_ROLES = setOf("owner", "admin", "manager")
    }
}

enum class ManagerRole(val value: String) {
    MANAGER("manager"),
    OWNER("owner"),
    MENTOR("mentor"),
    ADMIN("admin"),
    OPERATOR("operator"),
    TECHNICIAN("technician"),
}
