package com.aurafarmers.resqride.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.aurafarmers.resqride.data.model.ImuReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    // State flows
    private val _connectionStatus = MutableStateFlow(BleConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<BleConnectionStatus> = _connectionStatus.asStateFlow()

    private val _helmetStatus = MutableStateFlow(HelmetStatus())
    val helmetStatus: StateFlow<HelmetStatus> = _helmetStatus.asStateFlow()

    private val _liveImuStream = MutableSharedFlow<ImuReading>(extraBufferCapacity = 64)
    val liveImuStream: SharedFlow<ImuReading> = _liveImuStream.asSharedFlow()

    private val _crashAlertEvent = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val crashAlertEvent: SharedFlow<Boolean> = _crashAlertEvent.asSharedFlow()

    // Scanning
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: ""
            if (name.contains(BleConstants.TARGET_DEVICE_NAME_PREFIX, ignoreCase = true)) {
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "BLE Scan failed with code: $errorCode")
            _connectionStatus.value = BleConnectionStatus.DISCONNECTED
        }
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w("BleManager", "Bluetooth is disabled or not supported")
            return
        }
        _connectionStatus.value = BleConnectionStatus.SCANNING

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            // Stop scan after 15 seconds if nothing found
            mainHandler.postDelayed({
                if (_connectionStatus.value == BleConnectionStatus.SCANNING) {
                    stopScan()
                }
            }, 15000)
        } catch (e: Exception) {
            Log.e("BleManager", "Error starting scan", e)
            _connectionStatus.value = BleConnectionStatus.DISCONNECTED
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            if (_connectionStatus.value == BleConnectionStatus.SCANNING) {
                _connectionStatus.value = BleConnectionStatus.DISCONNECTED
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Error stopping scan", e)
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        _connectionStatus.value = BleConnectionStatus.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionStatus.value = BleConnectionStatus.DISCONNECTED
        _helmetStatus.value = _helmetStatus.value.copy(isConnected = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionStatus.value = BleConnectionStatus.CONNECTED
                _helmetStatus.value = _helmetStatus.value.copy(
                    isConnected = true,
                    deviceName = gatt.device.name ?: "ResQRide Helmet",
                    deviceAddress = gatt.device.address
                )
                gatt.discoverServices()
                startRssiPoller()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionStatus.value = BleConnectionStatus.DISCONNECTED
                _helmetStatus.value = _helmetStatus.value.copy(isConnected = false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return

                // 1. Enable Crash Event Notification
                val crashChar = service.getCharacteristic(BleConstants.CHAR_CRASH_EVENT_UUID)
                if (crashChar != null) {
                    enableNotification(gatt, crashChar)
                }

                // 2. Enable IMU Data Stream Notification
                val imuChar = service.getCharacteristic(BleConstants.CHAR_IMU_DATA_UUID)
                if (imuChar != null) {
                    mainHandler.postDelayed({
                        enableNotification(gatt, imuChar)
                    }, 500)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                BleConstants.CHAR_CRASH_EVENT_UUID -> {
                    val eventByte = value.getOrNull(0) ?: 0
                    if (eventByte.toInt() == 0x01) {
                        scope.launch { _crashAlertEvent.emit(true) }
                    } else if (eventByte.toInt() == 0x00) {
                        // Switch pressed on helmet - cancel alert
                        scope.launch { _crashAlertEvent.emit(false) }
                    }
                }
                BleConstants.CHAR_IMU_DATA_UUID -> {
                    parseImuPayload(value)
                }
                BleConstants.CHAR_BATTERY_LEVEL_UUID -> {
                    val battery = value.getOrNull(0)?.toInt() ?: 0
                    _helmetStatus.value = _helmetStatus.value.copy(batteryPercent = battery)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _helmetStatus.value = _helmetStatus.value.copy(rssiDbm = rssi)
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun parseImuPayload(bytes: ByteArray) {
        if (bytes.size >= 24) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val ax = buffer.float
            val ay = buffer.float
            val az = buffer.float
            val gx = buffer.float
            val gy = buffer.float
            val gz = buffer.float
            val reading = ImuReading(ax, ay, az, gx, gy, gz)
            scope.launch { _liveImuStream.emit(reading) }
        }
    }

    private fun startRssiPoller() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (_connectionStatus.value == BleConnectionStatus.CONNECTED) {
                    bluetoothGatt?.readRemoteRssi()
                    mainHandler.postDelayed(this, 5000)
                }
            }
        }, 3000)
    }

    // Testing / Simulation Helper: Safe crash simulation trigger
    fun triggerSimulatedCrash() {
        scope.launch {
            _crashAlertEvent.emit(true)
        }
    }

    // Testing / Simulation Helper: Safe simulation cancel
    fun triggerSimulatedCancel() {
        scope.launch {
            _crashAlertEvent.emit(false)
        }
    }

    // Testing / Simulation Helper: Emits mock IMU reading for testing telemetry screen
    fun emitMockImu(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        scope.launch {
            _liveImuStream.emit(ImuReading(ax, ay, az, gx, gy, gz))
        }
    }
}
