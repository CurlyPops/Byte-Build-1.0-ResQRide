package com.aurafarmers.resqride.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aurafarmers.resqride.R
import com.aurafarmers.resqride.ResQRideApplication
import com.aurafarmers.resqride.ble.BleManager
import com.aurafarmers.resqride.data.model.RideMetrics
import com.aurafarmers.resqride.sos.CrashAlertManager
import com.aurafarmers.resqride.ui.MainActivity
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RideTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var bleManager: BleManager
    private lateinit var crashAlertManager: CrashAlertManager

    private var previousLocation: Location? = null
    private var rideStartTime: Long = 0L
    private var durationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        crashAlertManager = CrashAlertManager.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupLocationUpdates()
        listenToBleCrashEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_RIDE) {
            stopRide()
            stopSelf()
            return START_NOT_STICKY
        }

        startRide()
        startForeground(NOTIFICATION_ID, buildRideNotification("0.0 km/h", "00:00"))
        return START_STICKY
    }

    private fun startRide() {
        rideStartTime = System.currentTimeMillis()
        previousLocation = null
        _rideMetrics.value = RideMetrics(isRiding = true, startTimestamp = rideStartTime)

        startLocationTracking()
        startDurationTimer()
        bleManager.startScan()
    }

    private fun stopRide() {
        stopLocationTracking()
        durationJob?.cancel()
        bleManager.stopScan()
        _rideMetrics.value = _rideMetrics.value.copy(isRiding = false, currentSpeedKmh = 0f)
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val elapsedSeconds = (System.currentTimeMillis() - rideStartTime) / 1000
                _rideMetrics.value = _rideMetrics.value.copy(durationSeconds = elapsedSeconds)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000
        ).setMinUpdateDistanceMeters(1.0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                updateMetricsWithLocation(location)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000
        ).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopLocationTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateMetricsWithLocation(location: Location) {
        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
        var totalDist = _rideMetrics.value.totalDistanceKm

        previousLocation?.let { prev ->
            val distMeters = prev.distanceTo(location)
            if (distMeters > 2.0f) {
                totalDist += (distMeters / 1000f)
            }
        }
        previousLocation = location

        _rideMetrics.value = _rideMetrics.value.copy(
            currentSpeedKmh = Math.round(speedKmh * 10f) / 10f,
            totalDistanceKm = Math.round(totalDist * 100f) / 100f,
            currentLatitude = location.latitude,
            currentLongitude = location.longitude
        )
    }

    private fun listenToBleCrashEvents() {
        serviceScope.launch {
            bleManager.crashAlertEvent.collect { isCrashDetected ->
                if (isCrashDetected) {
                    val lat = _rideMetrics.value.currentLatitude
                    val lon = _rideMetrics.value.currentLongitude
                    crashAlertManager.startCrashAlert(lat, lon)
                } else {
                    // Physical helmet switch was pressed to cancel
                    crashAlertManager.cancelAlert()
                }
            }
        }
    }

    private fun buildRideNotification(speedStr: String, timeStr: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RideTrackingService::class.java).apply {
            action = ACTION_STOP_RIDE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ResQRideApplication.CHANNEL_RIDE_TRACKING)
            .setContentTitle("ResQRide: Active Ride in Progress")
            .setContentText("Helmet Connected • Speed: $speedStr • Time: $timeStr")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "End Ride", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRide()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_RIDE = "com.aurafarmers.resqride.START_RIDE"
        const val ACTION_STOP_RIDE = "com.aurafarmers.resqride.STOP_RIDE"

        private val _rideMetrics = MutableStateFlow(RideMetrics())
        val rideMetrics: StateFlow<RideMetrics> = _rideMetrics.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RideTrackingService::class.java).apply {
                action = ACTION_START_RIDE
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RideTrackingService::class.java).apply {
                action = ACTION_STOP_RIDE
            }
            context.startService(intent)
        }
    }
}
