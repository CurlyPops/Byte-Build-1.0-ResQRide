package com.aurafarmers.resqride.data.model

import kotlin.math.sqrt

data class ImuReading(
    val ax: Float = 0f, // in g
    val ay: Float = 0f,
    val az: Float = 1f,
    val gx: Float = 0f, // in deg/s
    val gy: Float = 0f,
    val gz: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    val resultantAccelG: Float
        get() = sqrt(ax * ax + ay * ay + az * az)

    val resultantGyroDeg: Float
        get() = sqrt(gx * gx + gy * gy + gz * gz)
}

data class RideMetrics(
    val isRiding: Boolean = false,
    val currentSpeedKmh: Float = 0f,
    val totalDistanceKm: Float = 0f,
    val durationSeconds: Long = 0L,
    val currentLatitude: Double = 0.0,
    val currentLongitude: Double = 0.0,
    val startTimestamp: Long = 0L
)

data class HospitalInfo(
    val name: String,
    val distanceKm: Double,
    val phone: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
) {
    val mapsUrl: String
        get() = "https://maps.google.com/?q=$latitude,$longitude"
}
