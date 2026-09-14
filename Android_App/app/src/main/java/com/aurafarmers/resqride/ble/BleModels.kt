package com.aurafarmers.resqride.ble

import java.util.UUID

object BleConstants {
    // ResQRide Main Helmet Service
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    // Characteristic 1: 6-Axis IMU Raw Stream (Notify: 6 floats = 24 bytes)
    val CHAR_IMU_DATA_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    // Characteristic 2: Crash Event Alert (Notify / Indicate: 0x01 = Crash, 0x00 = Cancel)
    val CHAR_CRASH_EVENT_UUID: UUID = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e")

    // Characteristic 3: Standard BLE Battery Service & Level (0-100%)
    val CHAR_BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // Characteristic 4: Helmet Calibration & Diagnostics (Read/Write)
    val CHAR_CALIBRATION_UUID: UUID = UUID.fromString("d82098b1-4b15-4c07-b27e-8c3104618e47")

    // Standard Client Characteristic Configuration Descriptor (CCCD)
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TARGET_DEVICE_NAME_PREFIX = "ResQRide"
}

enum class BleConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

data class HelmetStatus(
    val isConnected: Boolean = false,
    val deviceName: String = "ResQRide Helmet",
    val deviceAddress: String = "ESP32-BLE",
    val batteryPercent: Int = 0,
    val rssiDbm: Int = 0,
    val isCalibrated: Boolean = false,
    val isRiding: Boolean = false
)
