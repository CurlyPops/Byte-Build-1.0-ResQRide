package com.aurafarmers.resqride.data.network

import android.content.Context
import android.util.Log
import com.aurafarmers.resqride.auth.FirebaseAuthManager
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UserProfileSyncService(
    private val context: Context,
    private val authManager: FirebaseAuthManager = FirebaseAuthManager(context),
    private val baseUrl: String = ApiConfig.BASE_URL
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "UserProfileSync"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Synchronizes the user profile and emergency contacts to the Supabase cloud
     * via the FastAPI backend endpoint POST /api/profile.
     */
    suspend fun syncProfileToCloud(
        profile: UserProfile,
        contacts: List<EmergencyContact>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (profile.uid.isBlank()) {
            Log.w(TAG, "Cannot sync profile with blank UID")
            return@withContext Result.failure(IllegalArgumentException("Profile UID is blank"))
        }

        try {
            val jsonPayload = JSONObject().apply {
                put("uid", profile.uid)
                put("fullName", profile.fullName)
                put("email", profile.email)
                put("phone", profile.phone)
                put("age", profile.age)
                put("gender", profile.gender)
                put("bloodGroup", profile.bloodGroup)

                val allergiesArr = JSONArray()
                profile.allergies.forEach { allergiesArr.put(it) }
                put("allergies", allergiesArr)

                val conditionsArr = JSONArray()
                profile.chronicConditions.forEach { conditionsArr.put(it) }
                put("chronicConditions", conditionsArr)

                val medsArr = JSONArray()
                profile.medications.forEach { medsArr.put(it) }
                put("medications", medsArr)

                put("emergencyNotes", profile.emergencyNotes)
                put("isProfileComplete", profile.isProfileComplete)
                put("prescriptionFileName", profile.prescriptionFileName ?: JSONObject.NULL)
                put("prescriptionFileId", profile.prescriptionFileId ?: JSONObject.NULL)
                put("prescriptionLocalUri", profile.prescriptionLocalUri ?: JSONObject.NULL)
                put("publicEmergencyUrl", profile.publicEmergencyUrl)

                val contactsArr = JSONArray()
                contacts.forEach { c ->
                    val cObj = JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("phone", c.phone)
                        put("relationship", c.relationship)
                        put("isPrimary", c.isPrimary)
                    }
                    contactsArr.put(cObj)
                }
                put("emergencyContacts", contactsArr)
            }

            val requestBody = jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val requestUrl = "${baseUrl.trimEnd('/')}/api/profile"

            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .post(requestBody)

            val token = authManager.getIdToken()
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Log.i(TAG, "Profile successfully synced to Supabase for ${profile.uid}")
                Result.success(true)
            } else {
                Log.w(TAG, "Profile sync returned code ${response.code}: $responseBody")
                Result.failure(Exception("Cloud sync failed (${response.code})"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during profile sync: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches stored profile data from Supabase for a given user ID.
     */
    suspend fun fetchProfileFromCloud(userId: String): Result<UserProfile?> =
        withContext(Dispatchers.IO) {
            if (userId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("User ID is blank"))
            }

            try {
                val requestUrl = "${baseUrl.trimEnd('/')}/api/profile/$userId"
                val request = Request.Builder()
                    .url(requestUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Fetch failed (${response.code})"))
                }

                val json = JSONObject(bodyStr)
                val profileObj = json.optJSONObject("profile")
                    ?: return@withContext Result.success(null)

                val allergiesJson = profileObj.optJSONArray("allergies")
                val allergiesList = mutableListOf<String>()
                if (allergiesJson != null) {
                    for (i in 0 until allergiesJson.length()) {
                        allergiesList.add(allergiesJson.getString(i))
                    }
                }

                val conditionsJson = profileObj.optJSONArray("chronicConditions")
                val conditionsList = mutableListOf<String>()
                if (conditionsJson != null) {
                    for (i in 0 until conditionsJson.length()) {
                        conditionsList.add(conditionsJson.getString(i))
                    }
                }

                val userProfile = UserProfile(
                    uid = profileObj.optString("uid", userId),
                    fullName = profileObj.optString("fullName", ""),
                    email = profileObj.optString("email", ""),
                    phone = profileObj.optString("phone", ""),
                    age = profileObj.optInt("age", 0),
                    gender = profileObj.optString("gender", ""),
                    bloodGroup = profileObj.optString("bloodGroup", ""),
                    allergies = allergiesList,
                    chronicConditions = conditionsList,
                    prescriptionFileName = profileObj.optString("prescriptionFileName").takeIf { it.isNotEmpty() },
                    prescriptionFileId = profileObj.optString("prescriptionFileId").takeIf { it.isNotEmpty() },
                    prescriptionLocalUri = profileObj.optString("prescriptionLocalUri").takeIf { it.isNotEmpty() },
                    emergencyNotes = profileObj.optString("emergencyNotes", ""),
                    isProfileComplete = profileObj.optBoolean("isProfileComplete", true),
                    publicEmergencyUrl = "https://resqride-oqhy.onrender.com/med/$userId"
                )

                Result.success(userProfile)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching profile from cloud: ${e.message}", e)
                Result.failure(e)
            }
        }
}
