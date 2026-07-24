package com.deviante.dto

import com.deviante.model.ManagerRecord
import kotlinx.serialization.Serializable

@Serializable
data class ManagerResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
)

@Serializable
data class UpdateManagerRequest(
    val fullName: String,
)

fun ManagerRecord.toResponse() = ManagerResponse(
    id = userId.toString(),
    email = email,
    fullName = fullName,
    role = role,
)
