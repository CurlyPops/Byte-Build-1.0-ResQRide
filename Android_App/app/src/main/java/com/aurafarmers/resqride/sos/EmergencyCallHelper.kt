package com.aurafarmers.resqride.sos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

object EmergencyCallHelper {

    const val NATIONAL_EMERGENCY_NUMBER = "112"

    /**
     * Dials or places a direct phone call:
     * - Uses ACTION_CALL if CALL_PHONE permission is granted.
     * - Gracefully falls back to ACTION_DIAL if permission is missing or restricted.
     * - Works seamlessly from both Activity and Application context.
     */
    fun makeCall(context: Context, rawPhoneNumber: String): Boolean {
        val cleanNumber = rawPhoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) {
            Toast.makeText(context, "Invalid phone number", Toast.LENGTH_SHORT).show()
            return false
        }

        val uri = Uri.fromParts("tel", cleanNumber, null)
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCallPermission) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                    if (context !is Activity) {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
                context.startActivity(callIntent)
                Log.i("EmergencyCallHelper", "Direct call placed to: $cleanNumber")
                return true
            } catch (e: Exception) {
                Log.e("EmergencyCallHelper", "Direct ACTION_CALL failed, falling back to dialer", e)
            }
        }

        // Fallback to ACTION_DIAL (never requires special runtime permissions, always succeeds)
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                if (context !is Activity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(dialIntent)
            Log.i("EmergencyCallHelper", "Dialer opened for: $cleanNumber")
            true
        } catch (e: Exception) {
            Log.e("EmergencyCallHelper", "ACTION_DIAL failed", e)
            Toast.makeText(context, "Could not open phone dialer", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Dials national emergency helpline (112)
     */
    fun makeEmergencyServicesCall(context: Context): Boolean {
        return makeCall(context, NATIONAL_EMERGENCY_NUMBER)
    }
}
