package com.aurafarmers.resqride.data.model

data class EmergencyContact(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val relationship: String,
    val isPrimary: Boolean = false
)
