package com.aurafarmers.resqride

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.aurafarmers.resqride.data.pref.UserPreferences

class ResQRideApplication : Application() {

    lateinit var preferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = UserPreferences(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 1. Channel for background ride tracking & BLE status (Low / Ongoing)
            val rideChannel = NotificationChannel(
                CHANNEL_RIDE_TRACKING,
                getString(R.string.ride_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ride_service_channel_desc)
                setShowBadge(false)
            }

            // 2. High-priority Emergency Crash Alarm Channel (Sound + Vibration)
            val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val crashChannel = NotificationChannel(
                CHANNEL_CRASH_ALERT,
                getString(R.string.crash_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.crash_alert_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                setSound(alertSound, audioAttributes)
            }

            notificationManager.createNotificationChannel(rideChannel)
            notificationManager.createNotificationChannel(crashChannel)
        }
    }

    companion object {
        const val CHANNEL_RIDE_TRACKING = "channel_resqride_tracking"
        const val CHANNEL_CRASH_ALERT = "channel_resqride_crash_alert"

        lateinit var instance: ResQRideApplication
            private set
    }
}
