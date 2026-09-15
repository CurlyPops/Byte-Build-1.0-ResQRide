package com.aurafarmers.resqride.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
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
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

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

    // Descriptor write queue for thread-safe sequential descriptor updates
    private val descriptorQueue = ConcurrentLinkedQueue<BluetoothGattDescriptor>()
    @Volatile
    private var isWritingDescriptor = false
    private val servicesDiscovered = AtomicBoolean(false)

    // Scanning
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val matchesName = name.contains(BleConstants.TARGET_DEVICE_NAME_PREFIX, ignoreCase = true)
            val matchesUuid = serviceUuids.any { it == BleConstants.SERVICE_UUID || it == BleConstants.LEGACY_SERVICE_UUID }
            if (matchesName || matchesUuid) {
                Log.i("BleManager", "Discovered ResQRide Helmet: $name (${device.address})")
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
            _connectionStatus.value = BleConnectionStatus.DISCONNECTED
            return
        }
        if (_connectionStatus.value == BleConnectionStatus.SCANNING) {
            return
        }
        _connectionStatus.value = BleConnectionStatus.SCANNING

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w("BleManager", "bluetoothLeScanner is null")
            _connectionStatus.value = BleConnectionStatus.DISCONNECTED
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.LEGACY_SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName("ResQRide-Helmet").build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
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
        disconnect()
        _connectionStatus.value = BleConnectionStatus.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("BleManager", "Error during disconnect", e)
        }
        bluetoothGatt = null
        descriptorQueue.clear()
        isWritingDescriptor = false
        servicesDiscovered.set(false)
        _connectionStatus.value = BleConnectionStatus.DISCONNECTED
        _helmetStatus.value = _helmetStatus.value.copy(isConnected = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i("BleManager", "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("BleManager", "GATT connection state error: $status, newState: $newState. Closing GATT.")
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.e("BleManager", "Error closing GATT", e)
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                descriptorQueue.clear()
                isWritingDescriptor = false
                servicesDiscovered.set(false)
                _connectionStatus.value = BleConnectionStatus.DISCONNECTED
                _helmetStatus.value = _helmetStatus.value.copy(isConnected = false)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BleManager", "GATT connected. Requesting MTU 512...")
                _connectionStatus.value = BleConnectionStatus.CONNECTED
                _helmetStatus.value = _helmetStatus.value.copy(
                    isConnected = true,
                    deviceName = gatt.device.name ?: "ResQRide Helmet",
                    deviceAddress = gatt.device.address
                )
                servicesDiscovered.set(false)

                // Request larger MTU so telemetry string or binary fits without truncation
                val requested = gatt.requestMtu(512)
                if (!requested) {
                    Log.w("BleManager", "requestMtu returned false, proceeding directly to discoverServices()")
                    mainHandler.postDelayed({
                        if (servicesDiscovered.compareAndSet(false, true)) {
                            gatt.discoverServices()
                        }
                    }, 600)
                } else {
                    // Safety timeout fallback if onMtuChanged isn't triggered by peripheral
                    mainHandler.postDelayed({
                        if (servicesDiscovered.compareAndSet(false, true)) {
                            Log.i("BleManager", "MTU callback fallback timeout, discovering services...")
                            gatt.discoverServices()
                        }
                    }, 1200)
                }
                startRssiPoller()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BleManager", "GATT disconnected.")
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.e("BleManager", "Error closing GATT on disconnect", e)
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                descriptorQueue.clear()
                isWritingDescriptor = false
                servicesDiscovered.set(false)
                _connectionStatus.value = BleConnectionStatus.DISCONNECTED
                _helmetStatus.value = _helmetStatus.value.copy(isConnected = false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i("BleManager", "onMtuChanged: mtu=$mtu, status=$status")
            if (servicesDiscovered.compareAndSet(false, true)) {
                mainHandler.postDelayed({
                    gatt.discoverServices()
                }, 300)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                    ?: gatt.getService(BleConstants.LEGACY_SERVICE_UUID)
                    ?: run {
                        Log.w("BleManager", "ResQRide service not found on device")
                        return
                    }

                // 1. Enable Crash Event Notification
                val crashChar = service.getCharacteristic(BleConstants.CHAR_CRASH_EVENT_UUID)
                    ?: service.getCharacteristic(BleConstants.LEGACY_CHAR_CRASH_EVENT_UUID)
                if (crashChar != null) {
                    enableNotification(gatt, crashChar)
                }

                // 2. Enable IMU Data Stream Notification
                val imuChar = service.getCharacteristic(BleConstants.CHAR_IMU_DATA_UUID)
                    ?: service.getCharacteristic(BleConstants.LEGACY_CHAR_IMU_DATA_UUID)
                if (imuChar != null) {
                    enableNotification(gatt, imuChar)
                }
            } else {
                Log.w("BleManager", "onServicesDiscovered failed with status: $status")
            }
        }

        // Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(characteristic.uuid, value)
        }

        // Android 12 and below
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                handleCharacteristicValue(characteristic.uuid, value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("BleManager", "onDescriptorWrite finished for: ${descriptor.characteristic?.uuid}, status=$status")
            isWritingDescriptor = false
            processDescriptorQueue(gatt)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _helmetStatus.value = _helmetStatus.value.copy(rssiDbm = rssi)
            }
        }
    }

    private fun handleCharacteristicValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.CHAR_CRASH_EVENT_UUID, BleConstants.LEGACY_CHAR_CRASH_EVENT_UUID -> {
                val eventByte = value.getOrNull(0) ?: 0
                if (eventByte.toInt() == 0x01) {
                    Log.w("BleManager", "CRASH EVENT NOTIFICATION RECEIVED FROM HELMET!")
                    scope.launch { _crashAlertEvent.emit(true) }
                } else if (eventByte.toInt() == 0x00) {
                    Log.i("BleManager", "Crash alert cancelled by physical helmet switch.")
                    scope.launch { _crashAlertEvent.emit(false) }
                }
            }
            BleConstants.CHAR_IMU_DATA_UUID, BleConstants.LEGACY_CHAR_IMU_DATA_UUID -> {
                parseImuPayload(value)
            }
            BleConstants.CHAR_BATTERY_LEVEL_UUID -> {
                val battery = value.getOrNull(0)?.toInt() ?: 0
                _helmetStatus.value = _helmetStatus.value.copy(batteryPercent = battery)
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            queueDescriptorWrite(gatt, descriptor)
        } else {
            Log.w("BleManager", "Descriptor 0x2902 not found for characteristic: ${characteristic.uuid}")
        }
    }

    private fun queueDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        descriptorQueue.add(descriptor)
        processDescriptorQueue(gatt)
    }

    @Synchronized
    private fun processDescriptorQueue(gatt: BluetoothGatt) {
        if (isWritingDescriptor) return
        val desc = descriptorQueue.poll() ?: return
        isWritingDescriptor = true

        val success = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Exception during writeDescriptor", e)
            false
        }

        if (!success) {
            Log.w("BleManager", "writeDescriptor returned false for ${desc.characteristic?.uuid}")
            isWritingDescriptor = false
            mainHandler.postDelayed({ processDescriptorQueue(gatt) }, 100)
        }
    }

    /**
     * Resilient IMU payload parser:
     * 1. CSV string: "ax,ay,az,gx,gy,gz" (e.g. "0.012,-0.034,0.981,1.2,-0.4,0.1")
     * 2. JSON string: {"ax":..., "ay":...}
     * 3. Binary 24-byte IEEE-754 float array (6 x 4-byte little-endian floats)
     */
    private fun parseImuPayload(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            // Check if string format first if bytes look like text (ASCII)
            val isAscii = bytes.all { it in 32..126 || it == 10.toByte() || it == 13.toByte() }
            if (isAscii) {
                val text = String(bytes, Charsets.UTF_8).trim()

                // Format A: CSV "ax,ay,az,gx,gy,gz"
                if (text.contains(",")) {
                    val parts = text.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size >= 6) {
                        val reading = ImuReading(
                            ax = parts[0],
                            ay = parts[1],
                            az = parts[2],
                            gx = parts[3],
                            gy = parts[4],
                            gz = parts[5]
                        )
                        scope.launch { _liveImuStream.emit(reading) }
                        return
                    }
                }

                // Format B: JSON {"ax":..., "ay":...}
                if (text.startsWith("{") && text.endsWith("}")) {
                    val json = JSONObject(text)
                    val ax = json.optDouble("ax", json.optDouble("accelX", 0.0)).toFloat()
                    val ay = json.optDouble("ay", json.optDouble("accelY", 0.0)).toFloat()
                    val az = json.optDouble("az", json.optDouble("accelZ", 0.0)).toFloat()
                    val gx = json.optDouble("gx", json.optDouble("gyroX", 0.0)).toFloat()
                    val gy = json.optDouble("gy", json.optDouble("gyroY", 0.0)).toFloat()
                    val gz = json.optDouble("gz", json.optDouble("gyroZ", 0.0)).toFloat()
                    scope.launch { _liveImuStream.emit(ImuReading(ax, ay, az, gx, gy, gz)) }
                    return
                }
            }

            // Format C: Binary 24-byte little-endian IEEE-754 floats (6 * 4 bytes)
            if (bytes.size >= 24) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val ax = buffer.float
                val ay = buffer.float
                val az = buffer.float
                val gx = buffer.float
                val gy = buffer.float
                val gz = buffer.float
                if (!ax.isNaN() && !ay.isNaN() && !az.isNaN()) {
                    val reading = ImuReading(ax, ay, az, gx, gy, gz)
                    scope.launch { _liveImuStream.emit(reading) }
                }
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Error parsing IMU payload: ${e.message}")
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
