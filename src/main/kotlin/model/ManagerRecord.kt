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
    val businessId: UUID? = null, // empresa a que o usuário pertence
) {
    fun isOwner(): Boolean = role == "owner"

    /** Excluir registros é permitido só ao administrador (owner ou admin) e ao gestor. */
    fun canDelete(): Boolean = role in DELETE_ROLES

    /** Catálogo de atividades e tela de Operações: só o administrador (owner ou admin). */
    fun keepsCatalog(): Boolean = role in CATALOG_ROLES

    /** Criar e alterar processos, máquinas, agendas e monitoramentos: administrador e gestor. */
    fun canEdit(): Boolean = role in EDIT_ROLES

    /** Anotar traces do processo: quem edita e o operador. */
    fun writesTraceNotes(): Boolean = role in NOTE_ROLES

    fun isTechnician(): Boolean = role == "technician"

    companion object {
        val DELETE_ROLES = setOf("owner", "admin", "manager")
        val CATALOG_ROLES = setOf("owner", "admin")
        val EDIT_ROLES = setOf("owner", "admin", "manager")
        val NOTE_ROLES = EDIT_ROLES + "operator"

        /** Papéis dos quais um processo ou máquina pode ser ocultado. */
        val HIDEABLE_ROLES = setOf("operator", "technician")
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
