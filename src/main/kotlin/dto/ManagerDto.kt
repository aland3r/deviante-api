package com.deviante.dto

import com.deviante.model.ManagerRecord
import com.deviante.variability.ClientVariant
import kotlinx.serialization.Serializable

@Serializable
data class ManagerResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
    // Variabilidade: a variante do produto que este cliente recebe.
    val client: String? = null,
    val plan: String? = null,
    val features: List<String> = emptyList(),
)

@Serializable
data class UpdateManagerRequest(
    val fullName: String,
)

fun ManagerRecord.toResponse(variant: ClientVariant? = null) = ManagerResponse(
    id = userId.toString(),
    email = email,
    fullName = fullName,
    role = role,
    client = variant?.client,
    plan = variant?.plan?.key,
    features = variant?.features?.map { it.key }?.sorted() ?: emptyList(),
)
