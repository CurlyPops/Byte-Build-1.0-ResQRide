package com.aurafarmers.resqride.data.pref

import android.content.Context
import android.content.SharedPreferences
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.model.UserProfile
import com.aurafarmers.resqride.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("resqride_user_prefs", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs.getBoolean(KEY_IS_LOGGED_IN, false))
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _contacts = MutableStateFlow(loadContacts())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    private val _userProfile = MutableStateFlow(loadUserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // 1. Auth Status
    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, loggedIn).apply()
        _isLoggedIn.value = loggedIn
    }

    fun logout() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
        _contacts.value = emptyList()
        _userProfile.value = UserProfile()
        _themeMode.value = ThemeMode.SYSTEM
    }

    // 2. Theme Setting
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    private fun loadThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    // 3. Countdown Duration Setting
    var countdownSeconds: Int
        get() = prefs.getInt(KEY_COUNTDOWN_SECONDS, 20)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply()

    // 4. Sensitivity (g-force threshold)
    var crashSensitivity: String
        get() = prefs.getString(KEY_SENSITIVITY, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString(KEY_SENSITIVITY, value).apply()

    // 5. Saved Helmet Bluetooth MAC / Name
    var helmetDeviceAddress: String?
        get() = prefs.getString(KEY_HELMET_MAC, null)
        set(value) = prefs.edit().putString(KEY_HELMET_MAC, value).apply()

    // 6. Emergency Contacts Persistence (Starts empty - user adds their own contacts)
    fun saveContacts(contactList: List<EmergencyContact>) {
        val array = JSONArray()
        for (c in contactList) {
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("phone", c.phone)
                put("relationship", c.relationship)
                put("isPrimary", c.isPrimary)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONTACTS_JSON, array.toString()).apply()
        _contacts.value = contactList
    }

    private fun loadContacts(): List<EmergencyContact> {
        val jsonStr = prefs.getString(KEY_CONTACTS_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            return emptyList()
        }
        val list = mutableListOf<EmergencyContact>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    EmergencyContact(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        phone = obj.optString("phone"),
                        relationship = obj.optString("relationship"),
                        isPrimary = obj.optBoolean("isPrimary")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // 7. Medical User Profile (Starts empty - user enters their details at onboarding)
    fun saveUserProfile(profile: UserProfile) {
        val obj = JSONObject().apply {
            put("uid", profile.uid)
            put("fullName", profile.fullName)
            put("email", profile.email)
            put("phone", profile.phone)
            put("age", profile.age)
            put("gender", profile.gender)
            put("bloodGroup", profile.bloodGroup)
            put("allergies", JSONArray(profile.allergies))
            put("chronicConditions", JSONArray(profile.chronicConditions))
            put("medications", JSONArray(profile.medications))
            put("prescriptionName", profile.prescriptionFileName ?: "")
            put("prescriptionUrl", profile.prescriptionUrl ?: "")
            put("prescriptionLocalUri", profile.prescriptionLocalUri ?: "")
            put("notes", profile.emergencyNotes)
            put("isProfileComplete", profile.isProfileComplete)
        }
        prefs.edit().putString(KEY_PROFILE_JSON, obj.toString()).apply()
        _userProfile.value = profile
    }

    private fun loadUserProfile(): UserProfile {
        val jsonStr = prefs.getString(KEY_PROFILE_JSON, null) ?: return UserProfile()
        return try {
            val obj = JSONObject(jsonStr)
            val allergiesJson = obj.optJSONArray("allergies")
            val allergiesList = mutableListOf<String>()
            if (allergiesJson != null) {
                for (i in 0 until allergiesJson.length()) {
                    allergiesList.add(allergiesJson.getString(i))
                }
            }
            val conditionsJson = obj.optJSONArray("chronicConditions")
            val conditionsList = mutableListOf<String>()
            if (conditionsJson != null) {
                for (i in 0 until conditionsJson.length()) {
                    conditionsList.add(conditionsJson.getString(i))
                }
            }

            val uid = obj.optString("uid", "")
            UserProfile(
                uid = uid,
                fullName = obj.optString("fullName", ""),
                email = obj.optString("email", ""),
                phone = obj.optString("phone", ""),
                age = obj.optInt("age", 0),
                gender = obj.optString("gender", ""),
                bloodGroup = obj.optString("bloodGroup", ""),
                allergies = allergiesList,
                chronicConditions = conditionsList,
                prescriptionFileName = obj.optString("prescriptionName").takeIf { it.isNotEmpty() },
                prescriptionLocalUri = obj.optString("prescriptionLocalUri").takeIf { it.isNotEmpty() },
                emergencyNotes = obj.optString("notes", ""),
                isProfileComplete = obj.optBoolean("isProfileComplete", false),
                publicEmergencyUrl = "https://resqride.web.app/med/$uid"
            )
        } catch (e: Exception) {
            UserProfile()
        }
    }

    companion object {
        private const val KEY_IS_LOGGED_IN = "pref_is_logged_in"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_COUNTDOWN_SECONDS = "pref_countdown_seconds"
        private const val KEY_SENSITIVITY = "pref_sensitivity"
        private const val KEY_HELMET_MAC = "pref_helmet_mac"
        private const val KEY_CONTACTS_JSON = "pref_contacts_json"
        private const val KEY_PROFILE_JSON = "pref_profile_json"
    }
}
