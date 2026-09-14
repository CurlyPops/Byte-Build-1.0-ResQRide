package com.aurafarmers.resqride.family

import android.content.Context
import com.aurafarmers.resqride.data.model.FamilyCircle
import com.aurafarmers.resqride.data.model.FamilyMemberLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

class FamilyManager(private val context: Context) {

    private val random = SecureRandom()
    private val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // omits ambiguous I, O, 0, 1

    // Starts NULL - no fake or random members!
    private val _currentCircle = MutableStateFlow<FamilyCircle?>(null)
    val currentCircle: StateFlow<FamilyCircle?> = _currentCircle.asStateFlow()

    /**
     * Generates a 6 or 8 character unique invite code.
     */
    fun generateUniqueInviteCode(length: Int = 8): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(allowedChars[random.nextInt(allowedChars.length)])
        }
        return sb.toString()
    }

    /**
     * Creates a new family circle with the user's real profile as the initial member.
     */
    fun createNewCircle(circleName: String, userName: String, userPhone: String): FamilyCircle {
        val code = generateUniqueInviteCode(8)
        val newCircle = FamilyCircle(
            circleId = "circle_${System.currentTimeMillis()}",
            circleName = circleName,
            inviteCode = code,
            ownerId = "owner_uid",
            members = listOf(
                FamilyMemberLocation(
                    userId = "user_me",
                    name = "$userName (You)",
                    phone = userPhone,
                    latitude = 0.0,
                    longitude = 0.0,
                    speedKmh = 0f,
                    isRiding = false,
                    hasCrashed = false,
                    batteryPercent = 100
                )
            )
        )
        _currentCircle.value = newCircle
        return newCircle
    }

    /**
     * Joins an existing family circle using the 6 or 8 character sequence code.
     */
    fun joinCircleWithCode(code: String, userName: String, userPhone: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.length < 6 || cleanCode.length > 8) {
            return false
        }

        val existing = _currentCircle.value
        val membersList = existing?.members?.toMutableList() ?: mutableListOf()

        if (membersList.none { it.userId == "user_me" }) {
            membersList.add(
                FamilyMemberLocation(
                    userId = "user_me",
                    name = "$userName (You)",
                    phone = userPhone,
                    latitude = 0.0,
                    longitude = 0.0,
                    speedKmh = 0f,
                    isRiding = false,
                    hasCrashed = false,
                    batteryPercent = 100
                )
            )
        }

        _currentCircle.value = FamilyCircle(
            circleId = existing?.circleId ?: "circle_${System.currentTimeMillis()}",
            circleName = existing?.circleName ?: "My Family Group",
            inviteCode = cleanCode,
            ownerId = existing?.ownerId ?: "external",
            members = membersList
        )
        return true
    }

    /**
     * Leave or delete current family circle
     */
    fun leaveCircle() {
        _currentCircle.value = null
    }

    /**
     * Updates rider location in real-time.
     */
    fun updateRiderLocation(
        latitude: Double,
        longitude: Double,
        speedKmh: Float,
        isRiding: Boolean,
        hasCrashed: Boolean
    ) {
        val current = _currentCircle.value ?: return
        val updated = current.members.map { m ->
            if (m.userId == "user_me") {
                m.copy(
                    latitude = latitude,
                    longitude = longitude,
                    speedKmh = speedKmh,
                    isRiding = isRiding,
                    hasCrashed = hasCrashed,
                    lastUpdated = System.currentTimeMillis()
                )
            } else m
        }
        _currentCircle.value = current.copy(members = updated)
    }

    companion object {
        @Volatile
        private var instance: FamilyManager? = null

        fun getInstance(context: Context): FamilyManager {
            return instance ?: synchronized(this) {
                instance ?: FamilyManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
