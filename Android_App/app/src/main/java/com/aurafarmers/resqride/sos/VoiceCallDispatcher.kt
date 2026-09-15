package com.aurafarmers.resqride.sos

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.model.UserProfile
import java.util.Locale

class VoiceCallDispatcher(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var repeatRunnable: Runnable? = null
    private var repeatCount = 0
    private val maxRepeats = 7 // Repeats every 7s for ~45-50s so recipient hears it regardless of answer delay

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(0.92f)
                tts?.setPitch(1.0f)

                // Route TTS through voice communication audio pipeline
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            }
        }
    }

    /**
     * Executes the emergency call procedure for the primary contact:
     * 1. Places an automated phone call using ACTION_CALL to the primary contact.
     * 2. Forces device speakerphone & maximizes volume streams.
     * 3. Starts continuous repetition voice loop so message is actively audible when answered.
     */
    @SuppressLint("MissingPermission")
    fun initiateEmergencyVoiceCall(
        primaryContact: EmergencyContact,
        userProfile: UserProfile,
        locationDescription: String
    ) {
        val cleanPhone = primaryContact.phone.filter { it.isDigit() || it == '+' }
        if (cleanPhone.isBlank()) {
            Log.w("VoiceCallDispatcher", "Cannot initiate call: Phone number is empty")
            return
        }

        launchPhoneCall(context, cleanPhone)
        startEmergencyVoiceLoop(userProfile, locationDescription)
    }

    /**
     * Loops the emergency announcement every 7 seconds over ~50 seconds.
     * Guarantees that whether the recipient picks up in 4s, 10s, 15s, or 25s,
     * the message is actively repeating and audible through the speakerphone.
     */
    fun startEmergencyVoiceLoop(userProfile: UserProfile, locationDescription: String) {
        stopEmergencyVoiceLoop()
        repeatCount = 0

        repeatRunnable = object : Runnable {
            override fun run() {
                if (repeatCount < maxRepeats) {
                    repeatCount++
                    forceSpeakerphone()
                    speakEmergencyMessage(userProfile, locationDescription)
                    mainHandler.postDelayed(this, 7000)
                } else {
                    stopEmergencyVoiceLoop()
                }
            }
        }

        // Start 4 seconds after call launch as ringing commences
        mainHandler.postDelayed(repeatRunnable!!, 4000)
    }

    fun stopEmergencyVoiceLoop() {
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = null
        repeatCount = 0
    }

    /**
     * Activates device speakerphone using modern communication device API (Android 12+)
     * or legacy isSpeakerphoneOn for older versions, and boosts volume to maximum.
     */
    fun forceSpeakerphone() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }

            // Maximize voice call and music stream volumes
            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)

            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        } catch (e: Exception) {
            Log.e("VoiceCallDispatcher", "Error setting speakerphone & volume", e)
        }
    }

    fun speakEmergencyMessage(userProfile: UserProfile, locationDescription: String) {
        if (!isTtsReady || tts == null) return

        forceSpeakerphone()

        val riderName = userProfile.fullName.ifBlank { "Your contact" }
        val speechText = "Emergency alert! This is an automated notification from ResQRide helmet. " +
                "$riderName has met with a motorcycle accident. " +
                "An emergency SMS with live GPS location has been sent to your phone. " +
                "Please check your SMS and attend immediately."

        tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "ResQRide_Emergency_TTS")
    }

    fun shutdown() {
        try {
            stopEmergencyVoiceLoop()
            tts?.stop()
            tts?.shutdown()

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            }
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e("VoiceCallDispatcher", "Error shutting down VoiceCallDispatcher", e)
        }
    }

    companion object {
        fun launchPhoneCall(context: Context, rawPhone: String) {
            val cleanPhone = rawPhone.filter { it.isDigit() || it == '+' }
            if (cleanPhone.isBlank()) return

            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$cleanPhone")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            try {
                context.startActivity(callIntent)
                Log.i("VoiceCallDispatcher", "Initiated emergency phone call to: $cleanPhone")
            } catch (e: Exception) {
                Log.e("VoiceCallDispatcher", "Failed to initiate direct call, attempting dial intent", e)
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$cleanPhone")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                } catch (err: Exception) {
                    Log.e("VoiceCallDispatcher", "Dialer error", err)
                }
            }
        }
    }
}
