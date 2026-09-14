package com.aurafarmers.resqride.data.model

data class UserProfile(
    val uid: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val age: Int = 0,
    val gender: String = "",

    // Medical History (User-provided)
    val bloodGroup: String = "",
    val allergies: List<String> = emptyList(),
    val chronicConditions: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val emergencyNotes: String = "",

    // Prescription Document
    val prescriptionFileName: String? = null,
    val prescriptionUrl: String? = null,
    val prescriptionLocalUri: String? = null,

    // Profile Completion & Auth status
    val isProfileComplete: Boolean = false,

    // Public Emergency Profile URL
    val publicEmergencyUrl: String = "https://resqride.web.app/med/$uid"
)
