package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class ManagerRecord(
    val id: UUID,
    val userId: UUID,
    val email: String,
    val fullName: String,
    val role: String = "manager", // manager, owner, mentor
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    fun isOwnerOrMentor(): Boolean = role in listOf("owner", "mentor")
}

enum class ManagerRole(val value: String) {
    MANAGER("manager"),
    OWNER("owner"),
    MENTOR("mentor"),
}
