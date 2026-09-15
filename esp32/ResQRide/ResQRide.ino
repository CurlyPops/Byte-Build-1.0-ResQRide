// =========================================================================
// ResQRide Smart Helmet Firmware — TinyML 1D CNN Crash Detection
//
// Board: ESP32-WROOM-32
// Sensor: MPU6050 via I2C (100 Hz, ±16g, ±2000 deg/s, DLPF 44 Hz)
// AI Model: 1D CNN trained on EPFL Helmet Impacts + VZCrash Road Dynamics
// Comms: BLE GATT Server
//
// ESP32 Arduino Core: 3.x
// =========================================================================

#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <math.h>

// TensorFlow Lite for Microcontrollers & Auto-Generated Model Header
#include "tensorflow/lite/micro/all_ops_resolver.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/schema/schema_generated.h"
#include "model_data.h"

// ========================================================================
// PIN DEFINITIONS
// ========================================================================

#define SDA_PIN          21
#define SCL_PIN          22

#define BUZZER_PIN       25
#define CANCEL_BTN_PIN   4

#define LED_POWER_PIN    2
#define LED_BLE_PIN      15

// ========================================================================
// MPU6050 REGISTERS
// ========================================================================

#define MPU6050_ADDR_1   0x68
#define MPU6050_ADDR_2   0x69

#define REG_PWR_MGMT_1   0x6B
#define REG_SMPLRT_DIV   0x19
#define REG_CONFIG       0x1A
#define REG_GYRO_CONFIG  0x1B
#define REG_ACCEL_CONFIG 0x1C
#define REG_ACCEL_XOUT_H 0x3B

uint8_t mpuAddress = 0;

// ========================================================================
// MPU6050 SCALE
// ========================================================================

// ±16g range: 2048 LSB/g
#define ACCEL_LSB_PER_G 2048.0f

// ±2000 deg/s range: 16.4 LSB/(deg/s)
#define GYRO_LSB_PER_DPS 16.4f

// ========================================================================
// 1D CNN MODEL & CIRCULAR BUFFER (Replaces manual threshold constants)
// ========================================================================

// CRASH_WINDOW_LEN (100), CRASH_CHANNELS (6), CRASH_THRESHOLD are in model_data.h

// Rolling circular buffer holding 1.0 second of data @ 100 Hz
float imuBuffer[CRASH_WINDOW_LEN][CRASH_CHANNELS];
int imuBufferHead = 0;

// Run 1D CNN inference every 10 samples (every 100 ms)
const int INFERENCE_STRIDE = 10;
int sampleCountSinceInference = 0;

// TFLM Runtime State
constexpr int kTensorArenaSize = 25 * 1024; // 25 KB SRAM tensor arena
uint8_t tensor_arena[kTensorArenaSize];

const tflite::Model* tflModel = nullptr;
tflite::MicroInterpreter* interpreter = nullptr;
TfLiteTensor* inputTensor = nullptr;
TfLiteTensor* outputTensor = nullptr;

float latestCrashProb = 0.0f;

// ========================================================================
// TIMING
// ========================================================================

const unsigned long SAMPLE_INTERVAL_US = 10000UL; // 100 Hz (10 ms)
const unsigned long BUZZER_DURATION_MS = 15000UL; // 15 seconds
const unsigned long BLE_BLINK_INTERVAL = 500UL;
const unsigned long DEBOUNCE_MS        = 200UL;
const int CALIBRATION_SAMPLES          = 500;

// ========================================================================
// BUZZER
// ========================================================================

#define BUZZER_FREQ_HZ    2700
#define BUZZER_RESOLUTION 8

// ========================================================================
// BLE UUIDs
// ========================================================================

#define DEVICE_NAME "ResQRide-Helmet"

#define SERVICE_UUID \
"0000ff00-0000-1000-8000-00805f9b34fb"

#define CHAR_TELEMETRY_UUID \
"0000ff01-0000-1000-8000-00805f9b34fb"

#define CHAR_CRASH_UUID \
"0000ff02-0000-1000-8000-00805f9b34fb"

#define CHAR_STATUS_UUID \
"0000ff03-0000-1000-8000-00805f9b34fb"

// ========================================================================
// CALIBRATION OFFSETS
// ========================================================================

float accelOffsetX = 0;
float accelOffsetY = 0;
float accelOffsetZ = 0;

float gyroOffsetX = 0;
float gyroOffsetY = 0;
float gyroOffsetZ = 0;

// ========================================================================
// LIVE SENSOR DATA
// ========================================================================

float accelX_g = 0;
float accelY_g = 0;
float accelZ_g = 0;

float gyroX_dps = 0;
float gyroY_dps = 0;
float gyroZ_dps = 0;

float accelMag = 0;
float gyroMag = 0;

// ========================================================================
// CRASH STATE
// ========================================================================

bool crashDetected = false;
bool buzzerActive = false;
unsigned long buzzerStartTime = 0;
unsigned long lastSampleTime = 0;

// ========================================================================
// CANCEL SWITCH (GPIO 4: Turn OFF then back ON to cancel false alarm)
// ========================================================================

unsigned long lastButtonPress = 0;
bool switchWasOff = false;
int initialSwitchState = LOW;

// ========================================================================
// BLE
// ========================================================================

BLEServer* pServer = nullptr;
BLECharacteristic* pTelemetryChar = nullptr;
BLECharacteristic* pCrashChar = nullptr;
BLECharacteristic* pStatusChar = nullptr;

bool deviceConnected = false;
unsigned long lastBleBlink = 0;
bool bleLedState = false;

// ========================================================================
// BLE CALLBACKS
// ========================================================================

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    deviceConnected = true;
    Serial.println("[BLE] Phone connected!");
  }

  void onDisconnect(BLEServer* server) override {
    deviceConnected = false;
    Serial.println("[BLE] Phone disconnected.");
    delay(200);
    server->getAdvertising()->start();
    Serial.println("[BLE] Advertising restarted.");
  }
};

// ========================================================================
// MPU6050 REGISTER WRITE
// ========================================================================

bool mpuWriteRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(mpuAddress);
  Wire.write(reg);
  Wire.write(value);
  uint8_t error = Wire.endTransmission();
  if (error != 0) {
    Serial.printf("[MPU6050] Write error: %d\n", error);
    return false;
  }
  return true;
}

// ========================================================================
// MPU6050 REGISTER READ
// ========================================================================

bool mpuReadRegisters(uint8_t reg, uint8_t* buffer, uint8_t length) {
  Wire.beginTransmission(mpuAddress);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  uint8_t received = Wire.requestFrom(mpuAddress, length, true);
  if (received != length) {
    return false;
  }

  for (uint8_t i = 0; i < length; i++) {
    buffer[i] = Wire.read();
  }
  return true;
}

// ========================================================================
// DETECT MPU6050
// ========================================================================

bool detectMPU6050(uint8_t address) {
  mpuAddress = address;

  // Wake sensor
  if (!mpuWriteRegister(REG_PWR_MGMT_1, 0x00)) return false;
  delay(100);

  // Configure 100 Hz
  if (!mpuWriteRegister(REG_SMPLRT_DIV, 9)) return false;

  // DLPF ~44 Hz
  if (!mpuWriteRegister(REG_CONFIG, 0x03)) return false;

  // Accelerometer ±16g
  if (!mpuWriteRegister(REG_ACCEL_CONFIG, 0x18)) return false;

  // Gyroscope ±2000 deg/s
  if (!mpuWriteRegister(REG_GYRO_CONFIG, 0x18)) return false;

  delay(100);

  // Try reading sensor data
  uint8_t data[6];
  if (!mpuReadRegisters(REG_ACCEL_XOUT_H, data, 6)) return false;

  return true;
}

// ========================================================================
// MPU6050 INITIALIZATION
// ========================================================================

bool mpuInit() {
  Serial.println();
  Serial.println("[MPU6050] Detecting sensor...");

  Serial.println("[MPU6050] Trying address 0x68...");
  if (detectMPU6050(MPU6050_ADDR_1)) {
    Serial.println("[MPU6050] Found at 0x68");
    return true;
  }

  Serial.println("[MPU6050] Trying address 0x69...");
  if (detectMPU6050(MPU6050_ADDR_2)) {
    Serial.println("[MPU6050] Found at 0x69");
    return true;
  }

  Serial.println("[MPU6050] Sensor not responding!");
  return false;
}

// ========================================================================
// RAW SENSOR READ
// ========================================================================

bool mpuReadRaw(int16_t* ax, int16_t* ay, int16_t* az,
                int16_t* gx, int16_t* gy, int16_t* gz) {
  uint8_t data[14];
  if (!mpuReadRegisters(REG_ACCEL_XOUT_H, data, 14)) {
    return false;
  }

  *ax = (int16_t)((data[0] << 8) | data[1]);
  *ay = (int16_t)((data[2] << 8) | data[3]);
  *az = (int16_t)((data[4] << 8) | data[5]);

  // data[6], data[7] = temperature

  *gx = (int16_t)((data[8] << 8) | data[9]);
  *gy = (int16_t)((data[10] << 8) | data[11]);
  *gz = (int16_t)((data[12] << 8) | data[13]);

  return true;
}

// ========================================================================
// CALIBRATION
// ========================================================================

void mpuCalibrate() {
  Serial.println();
  Serial.println("========================================");
  Serial.println("[CAL] CALIBRATION START");
  Serial.println("[CAL] Keep helmet completely STILL.");
  Serial.println("[CAL] Place helmet flat.");
  Serial.printf("[CAL] Samples: %d\n", CALIBRATION_SAMPLES);
  Serial.println("========================================");

  delay(1000);

  float sumAx = 0, sumAy = 0, sumAz = 0;
  float sumGx = 0, sumGy = 0, sumGz = 0;
  int16_t ax, ay, az, gx, gy, gz;
  int validSamples = 0;

  for (int i = 0; i < CALIBRATION_SAMPLES; i++) {
    if (mpuReadRaw(&ax, &ay, &az, &gx, &gy, &gz)) {
      sumAx += ax; sumAy += ay; sumAz += az;
      sumGx += gx; sumGy += gy; sumGz += gz;
      validSamples++;
    }
    if (i % 50 == 0) {
      Serial.printf("[CAL] %d / %d\n", i, CALIBRATION_SAMPLES);
    }
    delay(10);
  }

  if (validSamples < 400) {
    Serial.println("[CAL] ERROR: Too many sensor read failures!");
    return;
  }

  gyroOffsetX = sumGx / validSamples;
  gyroOffsetY = sumGy / validSamples;
  gyroOffsetZ = sumGz / validSamples;

  accelOffsetX = sumAx / validSamples;
  accelOffsetY = sumAy / validSamples;
  accelOffsetZ = (sumAz / validSamples) - 2048.0f; // 1g offset on Z

  Serial.println();
  Serial.println("[CAL] Calibration complete.");
  Serial.printf("[CAL] Accel offsets: X=%.1f Y=%.1f Z=%.1f\n", accelOffsetX, accelOffsetY, accelOffsetZ);
  Serial.printf("[CAL] Gyro offsets:  X=%.1f Y=%.1f Z=%.1f\n", gyroOffsetX, gyroOffsetY, gyroOffsetZ);
}

// ========================================================================
// READ CALIBRATED SENSOR
// ========================================================================

bool mpuReadCalibrated() {
  int16_t ax, ay, az, gx, gy, gz;
  if (!mpuReadRaw(&ax, &ay, &az, &gx, &gy, &gz)) {
    return false;
  }

  accelX_g = (ax - accelOffsetX) / ACCEL_LSB_PER_G;
  accelY_g = (ay - accelOffsetY) / ACCEL_LSB_PER_G;
  accelZ_g = (az - accelOffsetZ) / ACCEL_LSB_PER_G;

  gyroX_dps = (gx - gyroOffsetX) / GYRO_LSB_PER_DPS;
  gyroY_dps = (gy - gyroOffsetY) / GYRO_LSB_PER_DPS;
  gyroZ_dps = (gz - gyroOffsetZ) / GYRO_LSB_PER_DPS;

  accelMag = sqrtf(accelX_g * accelX_g + accelY_g * accelY_g + accelZ_g * accelZ_g);
  gyroMag  = sqrtf(gyroX_dps * gyroX_dps + gyroY_dps * gyroY_dps + gyroZ_dps * gyroZ_dps);

  return true;
}

// ========================================================================
// BLE INITIALIZATION
// ========================================================================

void bleInit() {
  Serial.println();
  Serial.println("[BLE] Starting BLE...");

  BLEDevice::init(DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pTelemetryChar = pService->createCharacteristic(
    CHAR_TELEMETRY_UUID, BLECharacteristic::PROPERTY_NOTIFY
  );
  pTelemetryChar->addDescriptor(new BLE2902());

  pCrashChar = pService->createCharacteristic(
    CHAR_CRASH_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  pCrashChar->addDescriptor(new BLE2902());
  uint8_t initialCrash = 0;
  pCrashChar->setValue(&initialCrash, 1);

  pStatusChar = pService->createCharacteristic(
    CHAR_STATUS_UUID, BLECharacteristic::PROPERTY_READ
  );
  pStatusChar->setValue("ResQRide Helmet v1.0 | 1D CNN TinyML | 100Hz");

  pService->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);

  BLEDevice::startAdvertising();

  Serial.println("[BLE] BLE started successfully.");
  Serial.printf("[BLE] Device: %s\n", DEVICE_NAME);
  Serial.println("[BLE] Advertising...");
}

// ========================================================================
// BLE TELEMETRY
// ========================================================================

void bleSendTelemetry() {
  if (!deviceConnected) return;

  float payload[6] = {
    accelX_g, accelY_g, accelZ_g,
    gyroX_dps, gyroY_dps, gyroZ_dps
  };

  pTelemetryChar->setValue((uint8_t*)payload, sizeof(payload));
  pTelemetryChar->notify();
}

// ========================================================================
// BLE CRASH ALERT
// ========================================================================

void bleSendCrashAlert(bool crash) {
  uint8_t value = crash ? 0x01 : 0x00;
  pCrashChar->setValue(&value, 1);

  if (deviceConnected) {
    pCrashChar->notify();
  }

  Serial.printf("[BLE] Crash event: %s\n", crash ? "CRASH" : "CANCELLED");
}

// ========================================================================
// BUZZER START / STOP / UPDATE
// ========================================================================

void buzzerStart() {
  buzzerStartTime = millis();
  buzzerActive = true;

  bool attached = ledcAttach(BUZZER_PIN, BUZZER_FREQ_HZ, BUZZER_RESOLUTION);
  if (!attached) {
    Serial.println("[BUZZER] ERROR: LEDC attach failed!");
    buzzerActive = false;
    return;
  }

  ledcWrite(BUZZER_PIN, 128);
  Serial.println("[BUZZER] ALERT SOUNDING!");
  Serial.println("[BUZZER] Flip switch to cancel.");
}

void buzzerStop() {
  if (buzzerActive) {
    ledcWrite(BUZZER_PIN, 0);
    ledcDetach(BUZZER_PIN);
  }
  buzzerActive = false;
  digitalWrite(BUZZER_PIN, LOW);
  Serial.println("[BUZZER] Silenced.");
}

void buzzerUpdate() {
  if (!buzzerActive) return;
  if (millis() - buzzerStartTime >= BUZZER_DURATION_MS) {
    buzzerStop();
    Serial.println("[BUZZER] 15-second timeout reached.");
  }
}

// ========================================================================
// CRASH DETECTION (1D CNN INFERENCE)
// ========================================================================

void checkForCrash() {
  if (crashDetected) {
    return;
  }

  // Fallback: If TFLM model/interpreter is not active, use high-G kinematic threshold detection
  // (Acceleration >= 4.0g AND Gyroscope >= 300 deg/s for 3 consecutive samples = 30ms)
  if (interpreter == nullptr) {
    static int consecutiveCrashSamples = 0;
    if (accelMag >= 4.0f && gyroMag >= 300.0f) {
      consecutiveCrashSamples++;
      if (consecutiveCrashSamples >= 3) {
        crashDetected = true;
        switchWasOff = false;
        initialSwitchState = digitalRead(CANCEL_BTN_PIN);

        Serial.println();
        Serial.println("========================================");
        Serial.println("  !!! CRASH DETECTED (KINEMATIC FALLBACK) !!!");
        Serial.println("========================================");
        Serial.printf("Acceleration:     %.2f g\n", accelMag);
        Serial.printf("Gyroscope:        %.1f deg/s\n", gyroMag);
        Serial.println("Alert activated (15s window).");
        Serial.println("Turn switch OFF then ON to cancel.");
        Serial.println("========================================");

        buzzerStart();
        bleSendCrashAlert(true);
        consecutiveCrashSamples = 0;
      }
    } else {
      consecutiveCrashSamples = 0;
    }
    return;
  }

  // Run CNN inference every 10 samples (every 100 ms)
  sampleCountSinceInference++;
  if (sampleCountSinceInference < INFERENCE_STRIDE) {
    return;
  }
  sampleCountSinceInference = 0;

  // Unroll circular buffer into chronological order for model input tensor
  for (int i = 0; i < CRASH_WINDOW_LEN; ++i) {
    int idx = (imuBufferHead + i) % CRASH_WINDOW_LEN;
    for (int c = 0; c < CRASH_CHANNELS; ++c) {
      inputTensor->data.f[i * CRASH_CHANNELS + c] = imuBuffer[idx][c];
    }
  }

  // Run 1D CNN Inference (< 8 ms on ESP32)
  if (interpreter->Invoke() != kTfLiteOk) {
    Serial.println("[TFLM] ERROR: Invoke() failed!");
    return;
  }

  latestCrashProb = outputTensor->data.f[0];

  // Check against tuned model decision threshold (from model_data.h)
  if (latestCrashProb >= CRASH_THRESHOLD) {
    crashDetected = true;
    switchWasOff = false;
    initialSwitchState = digitalRead(CANCEL_BTN_PIN);

    Serial.println();
    Serial.println("========================================");
    Serial.println("       !!! CRASH DETECTED (1D CNN) !!!");
    Serial.println("========================================");
    Serial.printf("Model Confidence: %.2f%%\n", latestCrashProb * 100.0f);
    Serial.printf("Acceleration:     %.2f g\n", accelMag);
    Serial.printf("Gyroscope:        %.1f deg/s\n", gyroMag);
    Serial.println("Alert activated (15s window).");
    Serial.println("Turn switch OFF then ON to cancel.");
    Serial.println("========================================");

    buzzerStart();
    bleSendCrashAlert(true);
  }
}

// ========================================================================
// CANCEL SWITCH (Turn OFF then back ON during alert window to cancel)
// ========================================================================

void handleCancelButton() {
  static int lastReading = -1;
  static unsigned long lastDebounceTime = 0;
  static int currentSwitchState = -1;

  int reading = digitalRead(CANCEL_BTN_PIN);

  if (lastReading == -1) {
    lastReading = reading;
    currentSwitchState = reading;
    initialSwitchState = reading;
  }

  if (reading != lastReading) {
    lastDebounceTime = millis();
    lastReading = reading;
  }

  if ((millis() - lastDebounceTime) >= 50) {
    if (reading != currentSwitchState) {
      currentSwitchState = reading;

      if (crashDetected) {
        if (currentSwitchState != initialSwitchState) {
          switchWasOff = true;
          Serial.println("[SWITCH] Switch turned OFF during alert window.");
        } else if (switchWasOff && (currentSwitchState == initialSwitchState)) {
          Serial.println();
          Serial.println("[CANCEL] False alarm cancelled! Switch cycled (OFF -> ON).");

          crashDetected = false;
          latestCrashProb = 0.0f;
          switchWasOff = false;

          buzzerStop();
          bleSendCrashAlert(false);
        }
      }
    }
  }
}

// ========================================================================
// LED MANAGEMENT
// ========================================================================

void updateLEDs() {
  digitalWrite(LED_POWER_PIN, HIGH);

  if (deviceConnected) {
    digitalWrite(LED_BLE_PIN, HIGH);
  } else {
    if (millis() - lastBleBlink >= BLE_BLINK_INTERVAL) {
      lastBleBlink = millis();
      bleLedState = !bleLedState;
      digitalWrite(LED_BLE_PIN, bleLedState);
    }
  }
}

// ========================================================================
// SERIAL LIVE DATA
// ========================================================================

void printLiveData() {
  static int counter = 0;
  counter++;
  if (counter < 50) return; // Print every 500 ms
  counter = 0;

  Serial.printf(
    "[IMU] A=%4.2fg G=%5.1fdps | CNN_Prob=%5.1f%% | BLE=%s | CRASH=%s\n",
    accelMag,
    gyroMag,
    latestCrashProb * 100.0f,
    deviceConnected ? "CONNECTED" : "ADVERTISING",
    crashDetected ? "YES" : "NO"
  );
}

// ========================================================================
// SETUP
// ========================================================================

void setup() {
  Serial.begin(115200);
  delay(2000);

  Serial.println();
  Serial.println("==============================================");
  Serial.println("      ResQRide Smart Helmet v1.0");
  Serial.println("      ESP32 + MPU6050 + 1D CNN TinyML");
  Serial.println("      100Hz | Deep Learning Collision Detection");
  Serial.println("==============================================");

  // Pin Configuration
  pinMode(LED_POWER_PIN, OUTPUT);
  pinMode(LED_BLE_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(CANCEL_BTN_PIN, INPUT_PULLUP);

  digitalWrite(LED_POWER_PIN, HIGH);
  digitalWrite(LED_BLE_PIN, LOW);
  digitalWrite(BUZZER_PIN, LOW);

  // I2C Setup
  Serial.println();
  Serial.println("[I2C] Starting I2C...");
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000);

  // Initialize MPU6050
  if (!mpuInit()) {
    Serial.println("[FATAL] MPU6050 not found. Check wiring.");
    while (true) {
      digitalWrite(LED_POWER_PIN, !digitalRead(LED_POWER_PIN));
      delay(250);
    }
  }

  // Calibrate MPU6050
  mpuCalibrate();

  // Initialize 1D CNN Model (TensorFlow Lite for Microcontrollers)
  Serial.println();
  Serial.println("[TFLM] Initializing ResQRide 1D CNN Model...");
  tflModel = tflite::GetModel(g_model);
  if (!tflModel || tflModel->version() != TFLITE_SCHEMA_VERSION) {
    Serial.println("[TFLM] Notice: Model schema not active — switching to Kinematic Threshold Crash Engine.");
    interpreter = nullptr;
  } else {
    static tflite::AllOpsResolver resolver;
    static tflite::MicroInterpreter static_interpreter(
        tflModel, resolver, tensor_arena, kTensorArenaSize);
    interpreter = &static_interpreter;

    if (interpreter->AllocateTensors() != kTfLiteOk) {
      Serial.println("[TFLM] ERROR: AllocateTensors() failed!");
    } else {
      inputTensor = interpreter->input(0);
      outputTensor = interpreter->output(0);
      Serial.println("[TFLM] 1D CNN Model initialized successfully!");
      Serial.printf("[TFLM] Decision Threshold: %.1f%%\n", CRASH_THRESHOLD * 100.0f);
    }
  }

  // Test Buzzer
  Serial.println();
  Serial.println("[BOOT] Testing buzzer...");
  bool buzzerAttached = ledcAttach(BUZZER_PIN, BUZZER_FREQ_HZ, BUZZER_RESOLUTION);
  if (buzzerAttached) {
    ledcWrite(BUZZER_PIN, 128);
    delay(200);
    ledcWrite(BUZZER_PIN, 0);
    ledcDetach(BUZZER_PIN);
    digitalWrite(BUZZER_PIN, LOW);
    Serial.println("[BOOT] Buzzer OK.");
  }

  // Initialize BLE
  bleInit();

  // Start Sampling
  lastSampleTime = micros();

  Serial.println();
  Serial.println("==============================================");
  Serial.println("[READY] HELMET ARMED & MONITORING (100Hz)");
  Serial.println("[READY] 1D CNN Real-Time Accident Inference Active");
  Serial.println("==============================================");
  Serial.println();
}

// ========================================================================
// MAIN LOOP
// ========================================================================

void loop() {
  unsigned long now = micros();
  if (now - lastSampleTime < SAMPLE_INTERVAL_US) {
    return;
  }
  lastSampleTime = now;

  // 1. Read calibrated IMU
  if (!mpuReadCalibrated()) {
    static unsigned long lastError = 0;
    if (millis() - lastError > 1000) {
      lastError = millis();
      Serial.println("[MPU6050] WARNING: Read failed.");
    }
    return;
  }

  // 2. Feed sample into the circular buffer for the 1D CNN
  imuBuffer[imuBufferHead][0] = accelX_g;
  imuBuffer[imuBufferHead][1] = accelY_g;
  imuBuffer[imuBufferHead][2] = accelZ_g;
  imuBuffer[imuBufferHead][3] = gyroX_dps;
  imuBuffer[imuBufferHead][4] = gyroY_dps;
  imuBuffer[imuBufferHead][5] = gyroZ_dps;
  imuBufferHead = (imuBufferHead + 1) % CRASH_WINDOW_LEN;

  // 3. 1D CNN Crash detection inference
  checkForCrash();

  // 4. BLE telemetry stream
  bleSendTelemetry();

  // 5. Check cancel button
  handleCancelButton();

  // 6. Update buzzer timer
  buzzerUpdate();

  // 7. Update status LEDs
  updateLEDs();

  // 8. Output telemetry to Serial Monitor
  printLiveData();
}