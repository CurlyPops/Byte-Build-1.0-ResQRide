package com.aurafarmers.resqride.sos

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import com.aurafarmers.resqride.ResQRideApplication
import com.aurafarmers.resqride.data.model.HospitalInfo
import com.aurafarmers.resqride.ui.alert.CrashAlertActivity
import com.aurafarmers.resqride.util.PermissionHelper
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CrashAlertManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    val smsDispatcher = SmsDispatcher(context)
    val hospitalFinder = HospitalFinder(context)
    val voiceCallDispatcher = VoiceCallDispatcher(context)

    private val _isAlertActive = MutableStateFlow(false)
    val isAlertActive: StateFlow<Boolean> = _isAlertActive.asStateFlow()

    private val _isSosDispatched = MutableStateFlow(false)
    val isSosDispatched: StateFlow<Boolean> = _isSosDispatched.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(20)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _nearbyHospitals = MutableStateFlow<List<HospitalInfo>>(emptyList())
    val nearbyHospitals: StateFlow<List<HospitalInfo>> = _nearbyHospitals.asStateFlow()

    var currentCrashLat: Double = 0.0
        private set
    var currentCrashLon: Double = 0.0
        private set

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Triggered when a crash is detected by ESP32 or the simulation button is pressed.
     */
    fun startCrashAlert(latitude: Double = 0.0, longitude: Double = 0.0) {
        if (_isAlertActive.value) return
        _isAlertActive.value = true
        _isSosDispatched.value = false

        val initialSeconds = ResQRideApplication.instance.preferences.countdownSeconds
        _remainingSeconds.value = initialSeconds

        // Set baseline coordinates (fallback to safety coordinates if none provided)
        currentCrashLat = if (latitude != 0.0) latitude else 12.9716
        currentCrashLon = if (longitude != 0.0) longitude else 77.5946

        // 1. Fetch real device GPS & nearby 5km hospitals in background
        scope.launch(Dispatchers.IO) {
            if (latitude == 0.0 && longitude == 0.0 && PermissionHelper.isFineLocationGranted(context)) {
                try {
                    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                    val loc = fusedClient.lastLocation.await()
                    if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                        currentCrashLat = loc.latitude
                        currentCrashLon = loc.longitude
                    }
                } catch (e: Exception) {
                    Log.w("CrashAlertManager", "Could not fetch real-time GPS location", e)
                }
            }

            val hospitals = hospitalFinder.getNearbyHospitals(currentCrashLat, currentCrashLon, 5.0)
            _nearbyHospitals.value = hospitals
        }

        // 2. Start loud audio alarm & continuous vibration
        startAudioAlarm()
        startVibration()

        // 3. Launch full-screen lockscreen activity
        launchAlertActivity()

        // 4. Start Countdown
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer((initialSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _remainingSeconds.value = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                _remainingSeconds.value = 0
                dispatchEmergencySos(currentCrashLat, currentCrashLon)
            }
        }.start()
    }

    /**
     * Cancel the alert (User tapped "I'm OK" or pressed helmet switch)
     */
    fun cancelAlert() {
        if (!_isAlertActive.value) return
        countdownTimer?.cancel()
        stopAudioAlarm()
        stopVibration()
        _isAlertActive.value = false
        _isSosDispatched.value = false
        voiceCallDispatcher.stopEmergencyVoiceLoop()
        Log.i("CrashAlertManager", "Crash alert cancelled by user / helmet switch. False alarm recorded.")
    }

    fun dismissSos() {
        _isSosDispatched.value = false
        voiceCallDispatcher.stopEmergencyVoiceLoop()
    }

    /**
     * Dispatches emergency SOS when countdown reaches 0 or user taps "SOS Now" immediately.
     */
    fun dispatchEmergencySos(latitude: Double = currentCrashLat, longitude: Double = currentCrashLon) {
        countdownTimer?.cancel()
        stopAudioAlarm()
        stopVibration()
        _isAlertActive.value = false
        _isSosDispatched.value = true

        val prefs = ResQRideApplication.instance.preferences
        val contacts = prefs.contacts.value
        val userProfile = prefs.userProfile.value

        val targetLat = if (latitude != 0.0) latitude else currentCrashLat
        val targetLon = if (longitude != 0.0) longitude else currentCrashLon

        val hospitals = if (_nearbyHospitals.value.isNotEmpty()) {
            _nearbyHospitals.value
        } else {
            hospitalFinder.getNearbyHospitals(targetLat, targetLon, 5.0)
        }

        // 1. Place automated AI Voice Call to primary contact immediately
        val primaryContact = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()
        if (primaryContact != null && primaryContact.phone.isNotBlank()) {
            voiceCallDispatcher.initiateEmergencyVoiceCall(
                primaryContact = primaryContact,
                userProfile = userProfile,
                locationDescription = "$targetLat, $targetLon"
            )
        } else {
            // Fallback to national emergency helpline 112 if no contact is configured
            VoiceCallDispatcher.launchPhoneCall(context, "112")
        }

        // 2. Send SMS to all emergency contacts quietly in background
        scope.launch(Dispatchers.IO) {
            smsDispatcher.sendEmergencyAlerts(
                contacts = contacts,
                userProfile = userProfile,
                latitude = targetLat,
                longitude = targetLon,
                nearbyHospitals = hospitals
            )
        }
    }

    private fun launchAlertActivity() {
        val intent = Intent(context, CrashAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    private fun startAudioAlarm() {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("CrashAlertManager", "Error starting alarm sound", e)
        }
    }

    private fun stopAudioAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("CrashAlertManager", "Error stopping alarm sound", e)
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    companion object {
        @Volatile
        private var instance: CrashAlertManager? = null

        fun getInstance(context: Context): CrashAlertManager {
            return instance ?: synchronized(this) {
                instance ?: CrashAlertManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
