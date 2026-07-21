package com.deviante.dto

import com.deviante.model.ManagerRecord
import kotlinx.serialization.Serializable

@Serializable
data class ManagerResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val firstLanguage: String,
    val targetLanguage: String,
    val locationEnabled: Boolean,
    val basedIn: String? = null,
)

@Serializable
data class UpdateManagerRequest(
    val fullName: String,
    val firstLanguage: String,
    val targetLanguage: String,
    val locationEnabled: Boolean = false,
    val basedIn: String? = null,
)

fun ManagerRecord.toResponse() = ManagerResponse(
    id = userId.toString(),
    email = email,
    fullName = fullName,
    firstLanguage = firstLanguage,
    targetLanguage = targetLanguage,
    locationEnabled = locationEnabled,
    basedIn = basedIn,
)
