package com.aurafarmers.resqride.sos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.model.HospitalInfo
import com.aurafarmers.resqride.data.model.UserProfile
import java.util.Locale

class SmsDispatcher(private val context: Context) {

    private fun getSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /**
     * Builds and sends multi-contact emergency SMS messages containing:
     * - Crash notification
     * - Google Maps & Apple Maps navigation links
     * - Top nearby 5km emergency hospitals with direct contact numbers
     * - Critical medical history (Blood group, allergies)
     */
    fun sendEmergencyAlerts(
        contacts: List<EmergencyContact>,
        userProfile: UserProfile,
        latitude: Double,
        longitude: Double,
        nearbyHospitals: List<HospitalInfo>
    ): Boolean {
        if (contacts.isEmpty()) {
            Log.w("SmsDispatcher", "No emergency contacts configured to send SMS")
            return false
        }

        val messageText = buildEmergencyMessage(userProfile, latitude, longitude, nearbyHospitals)
        val smsManager = getSmsManager()
        var anySuccess = false
        var permissionDenied = false

        for (contact in contacts) {
            val cleanPhone = contact.phone.replace(" ", "").replace("-", "")
            try {
                val parts = smsManager.divideMessage(messageText)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(cleanPhone, null, messageText, null, null)
                }
                anySuccess = true
                Log.i("SmsDispatcher", "Emergency SMS dispatched to ${contact.name} ($cleanPhone)")
            } catch (e: SecurityException) {
                permissionDenied = true
                Log.e("SmsDispatcher", "SMS permission denied by Android restricted settings for ${contact.name}", e)
            } catch (e: Exception) {
                Log.e("SmsDispatcher", "Failed to send SMS to ${contact.name}", e)
            }
        }

        // If background SMS was blocked by Android permissions, open native SMS app fallback
        if (!anySuccess && permissionDenied) {
            val primaryContact = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()
            if (primaryContact != null) {
                launchSmsFallback(primaryContact, messageText)
            }
        }

        return anySuccess
    }

    fun buildEmergencyMessage(
        userProfile: UserProfile,
        latitude: Double,
        longitude: Double,
        nearbyHospitals: List<HospitalInfo>
    ): String {
        val effectiveLat = if (latitude != 0.0) latitude else 12.9716
        val effectiveLon = if (longitude != 0.0) longitude else 77.5946
        val googleMapsUrl = "https://maps.google.com/?q=$effectiveLat,$effectiveLon"
        val appleMapsUrl = "https://maps.apple.com/?q=$effectiveLat,$effectiveLon"

        val effectiveHospitals = if (nearbyHospitals.isNotEmpty()) {
            nearbyHospitals
        } else {
            HospitalFinder(context).getNearbyHospitals(effectiveLat, effectiveLon, 5.0)
        }

        val hospitalsFormatted = buildString {
            append("\nNearby 5km Hospitals:\n")
            effectiveHospitals.take(3).forEachIndexed { idx, h ->
                append("${idx + 1}. ${h.name} (${h.distanceKm}km) Ph: ${h.phone}\n")
            }
        }

        val riderName = userProfile.fullName.ifBlank { "Rider" }
        val bloodInfo = if (userProfile.bloodGroup.isNotBlank()) "Blood: ${userProfile.bloodGroup}" else ""
        val allergyInfo = if (userProfile.allergies.isNotEmpty()) "Allergies: ${userProfile.allergies.joinToString()}" else ""
        val medInfo = listOf(bloodInfo, allergyInfo).filter { it.isNotBlank() }.joinToString(" | ")

        return buildString {
            append("EMERGENCY ALERT: $riderName met with a motorcycle accident!\n\n")
            append("Location: ${String.format(Locale.US, "%.5f", effectiveLat)}, ${String.format(Locale.US, "%.5f", effectiveLon)}\n")
            append("Google Maps: $googleMapsUrl\n")
            append("Apple Maps: $appleMapsUrl\n")
            if (medInfo.isNotBlank()) {
                append("\n$medInfo\n")
            }
            append(hospitalsFormatted)
            append("\nMedical Profile: ${userProfile.publicEmergencyUrl}\n")
            append("- Sent automatically via ResQRide Helmet")
        }
    }

    /**
     * Fallback that opens the native SMS app pre-populated with emergency text & contact number
     * in case Android's sideload restricted settings prevented silent background SMS.
     */
    fun launchSmsFallback(contact: EmergencyContact, message: String) {
        try {
            val cleanPhone = contact.phone.replace(" ", "").replace("-", "")
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$cleanPhone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("SmsDispatcher", "Failed to launch SMS fallback app", e)
        }
    }
}
