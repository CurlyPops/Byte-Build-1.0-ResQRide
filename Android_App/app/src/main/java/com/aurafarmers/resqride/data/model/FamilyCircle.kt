package com.aurafarmers.resqride.data.model

data class FamilyMemberLocation(
    val userId: String,
    val name: String,
    val phone: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val isRiding: Boolean,
    val hasCrashed: Boolean,
    val batteryPercent: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class FamilyCircle(
    val circleId: String = "",
    val circleName: String = "My Family Circle",
    val inviteCode: String = "", // 6 or 8 character alphanumeric code, e.g. "RIDE9X2P"
    val ownerId: String = "",
    val members: List<FamilyMemberLocation> = emptyList()
)
