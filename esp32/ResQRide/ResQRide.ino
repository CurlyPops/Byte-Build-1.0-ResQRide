// =========================================================================
// ResQRide Smart Helmet Firmware
//
// Board: ESP32-WROOM-32
// Sensor: MPU6050 via I2C
// Comms: BLE GATT Server
//
// ESP32 Arduino Core: 3.x
//
// MPU6050:
//   - NO WHO_AM_I CHECK
//   - Automatically tries 0x68 and 0x69
//
// Crash Detection:
//   Acceleration >= 4g
//   AND
//   Gyroscope >= 300 deg/s
//   FOR 3 consecutive samples
//   = 30 ms at 100 Hz
//
// =========================================================================

#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <math.h>

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
// MPU6050
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

// ±16g
#define ACCEL_LSB_PER_G 2048.0f

// ±2000 deg/s
#define GYRO_LSB_PER_DPS 16.4f

// ========================================================================
// CRASH THRESHOLDS
// ========================================================================

const float ACCEL_THRESHOLD_G  = 4.0f;
const float GYRO_THRESHOLD_DPS = 300.0f;

const int SUSTAINED_COUNT = 3;

// ========================================================================
// TIMING
// ========================================================================

const unsigned long SAMPLE_INTERVAL_US = 10000UL; // 100 Hz

const unsigned long BUZZER_DURATION_MS = 15000UL;

const unsigned long BLE_BLINK_INTERVAL = 500UL;

const unsigned long DEBOUNCE_MS = 200UL;

const int CALIBRATION_SAMPLES = 500;

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

int sustainedHitCount = 0;

bool crashDetected = false;

bool buzzerActive = false;

unsigned long buzzerStartTime = 0;

unsigned long lastSampleTime = 0;

// ========================================================================
// BUTTON
// ========================================================================

unsigned long lastButtonPress = 0;

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

    Serial.println(
      "[BLE] Phone connected!"
    );
  }

  void onDisconnect(BLEServer* server) override {

    deviceConnected = false;

    Serial.println(
      "[BLE] Phone disconnected."
    );

    delay(200);

    server->getAdvertising()->start();

    Serial.println(
      "[BLE] Advertising restarted."
    );
  }
};

// ========================================================================
// MPU6050 REGISTER WRITE
// ========================================================================

bool mpuWriteRegister(
  uint8_t reg,
  uint8_t value
) {

  Wire.beginTransmission(
    mpuAddress
  );

  Wire.write(reg);

  Wire.write(value);

  uint8_t error =
    Wire.endTransmission();

  if (error != 0) {

    Serial.printf(
      "[MPU6050] Write error: %d\n",
      error
    );

    return false;
  }

  return true;
}

// ========================================================================
// MPU6050 REGISTER READ
// ========================================================================

bool mpuReadRegisters(
  uint8_t reg,
  uint8_t* buffer,
  uint8_t length
) {

  Wire.beginTransmission(
    mpuAddress
  );

  Wire.write(reg);

  if (
    Wire.endTransmission(false)
    != 0
  ) {

    return false;
  }

  uint8_t received =
    Wire.requestFrom(
      mpuAddress,
      length,
      true
    );

  if (received != length) {

    return false;
  }

  for (
    uint8_t i = 0;
    i < length;
    i++
  ) {

    buffer[i] =
      Wire.read();
  }

  return true;
}

// ========================================================================
// DETECT MPU6050
// ========================================================================

bool detectMPU6050(
  uint8_t address
) {

  mpuAddress = address;

  // Wake sensor

  if (
    !mpuWriteRegister(
      REG_PWR_MGMT_1,
      0x00
    )
  ) {

    return false;
  }

  delay(100);

  // Configure 100 Hz

  if (
    !mpuWriteRegister(
      REG_SMPLRT_DIV,
      9
    )
  ) {

    return false;
  }

  // DLPF ~44 Hz

  if (
    !mpuWriteRegister(
      REG_CONFIG,
      0x03
    )
  ) {

    return false;
  }

  // Accelerometer ±16g

  if (
    !mpuWriteRegister(
      REG_ACCEL_CONFIG,
      0x18
    )
  ) {

    return false;
  }

  // Gyroscope ±2000 deg/s

  if (
    !mpuWriteRegister(
      REG_GYRO_CONFIG,
      0x18
    )
  ) {

    return false;
  }

  delay(100);

  // Try reading sensor data

  uint8_t data[6];

  if (
    !mpuReadRegisters(
      REG_ACCEL_XOUT_H,
      data,
      6
    )
  ) {

    return false;
  }

  return true;
}

// ========================================================================
// MPU6050 INITIALIZATION
// ========================================================================

bool mpuInit() {

  Serial.println();
  Serial.println(
    "[MPU6050] Detecting sensor..."
  );

  // Try 0x68

  Serial.println(
    "[MPU6050] Trying address 0x68..."
  );

  if (
    detectMPU6050(
      MPU6050_ADDR_1
    )
  ) {

    Serial.println(
      "[MPU6050] Found at 0x68"
    );

    return true;
  }

  // Try 0x69

  Serial.println(
    "[MPU6050] Trying address 0x69..."
  );

  if (
    detectMPU6050(
      MPU6050_ADDR_2
    )
  ) {

    Serial.println(
      "[MPU6050] Found at 0x69"
    );

    return true;
  }

  Serial.println(
    "[MPU6050] Sensor not responding!"
  );

  return false;
}

// ========================================================================
// RAW SENSOR READ
// ========================================================================

bool mpuReadRaw(
  int16_t* ax,
  int16_t* ay,
  int16_t* az,
  int16_t* gx,
  int16_t* gy,
  int16_t* gz
) {

  uint8_t data[14];

  if (
    !mpuReadRegisters(
      REG_ACCEL_XOUT_H,
      data,
      14
    )
  ) {

    return false;
  }

  *ax =
    (int16_t)(
      (data[0] << 8) |
      data[1]
    );

  *ay =
    (int16_t)(
      (data[2] << 8) |
      data[3]
    );

  *az =
    (int16_t)(
      (data[4] << 8) |
      data[5]
    );

  // data[6], data[7] = temperature

  *gx =
    (int16_t)(
      (data[8] << 8) |
      data[9]
    );

  *gy =
    (int16_t)(
      (data[10] << 8) |
      data[11]
    );

  *gz =
    (int16_t)(
      (data[12] << 8) |
      data[13]
    );

  return true;
}

// ========================================================================
// CALIBRATION
// ========================================================================

void mpuCalibrate() {

  Serial.println();
  Serial.println(
    "========================================"
  );

  Serial.println(
    "[CAL] CALIBRATION START"
  );

  Serial.println(
    "[CAL] Keep helmet completely STILL."
  );

  Serial.println(
    "[CAL] Place helmet flat."
  );

  Serial.printf(
    "[CAL] Samples: %d\n",
    CALIBRATION_SAMPLES
  );

  Serial.println(
    "========================================"
  );

  delay(1000);

  float sumAx = 0;
  float sumAy = 0;
  float sumAz = 0;

  float sumGx = 0;
  float sumGy = 0;
  float sumGz = 0;

  int16_t ax;
  int16_t ay;
  int16_t az;

  int16_t gx;
  int16_t gy;
  int16_t gz;

  int validSamples = 0;

  for (
    int i = 0;
    i < CALIBRATION_SAMPLES;
    i++
  ) {

    if (
      mpuReadRaw(
        &ax,
        &ay,
        &az,
        &gx,
        &gy,
        &gz
      )
    ) {

      sumAx += ax;
      sumAy += ay;
      sumAz += az;

      sumGx += gx;
      sumGy += gy;
      sumGz += gz;

      validSamples++;
    }

    if (
      i % 50 == 0
    ) {

      Serial.printf(
        "[CAL] %d / %d\n",
        i,
        CALIBRATION_SAMPLES
      );
    }

    delay(10);
  }

  if (
    validSamples < 400
  ) {

    Serial.println(
      "[CAL] ERROR: Too many sensor read failures!"
    );

    return;
  }

  // Gyroscope offsets

  gyroOffsetX =
    sumGx / validSamples;

  gyroOffsetY =
    sumGy / validSamples;

  gyroOffsetZ =
    sumGz / validSamples;

  // Accelerometer offsets
  //
  // Helmet flat:
  // X = 0g
  // Y = 0g
  // Z = +1g
  //
  // At ±16g:
  // 2048 LSB = 1g

  accelOffsetX =
    sumAx / validSamples;

  accelOffsetY =
    sumAy / validSamples;

  accelOffsetZ =
    (sumAz / validSamples)
    - 2048.0f;

  Serial.println();

  Serial.println(
    "[CAL] Calibration complete."
  );

  Serial.printf(
    "[CAL] Accel offsets: "
    "X=%.1f Y=%.1f Z=%.1f\n",
    accelOffsetX,
    accelOffsetY,
    accelOffsetZ
  );

  Serial.printf(
    "[CAL] Gyro offsets: "
    "X=%.1f Y=%.1f Z=%.1f\n",
    gyroOffsetX,
    gyroOffsetY,
    gyroOffsetZ
  );
}

// ========================================================================
// READ CALIBRATED SENSOR
// ========================================================================

bool mpuReadCalibrated() {

  int16_t ax;
  int16_t ay;
  int16_t az;

  int16_t gx;
  int16_t gy;
  int16_t gz;

  if (
    !mpuReadRaw(
      &ax,
      &ay,
      &az,
      &gx,
      &gy,
      &gz
    )
  ) {

    return false;
  }

  accelX_g =
    (ax - accelOffsetX)
    / ACCEL_LSB_PER_G;

  accelY_g =
    (ay - accelOffsetY)
    / ACCEL_LSB_PER_G;

  accelZ_g =
    (az - accelOffsetZ)
    / ACCEL_LSB_PER_G;

  gyroX_dps =
    (gx - gyroOffsetX)
    / GYRO_LSB_PER_DPS;

  gyroY_dps =
    (gy - gyroOffsetY)
    / GYRO_LSB_PER_DPS;

  gyroZ_dps =
    (gz - gyroOffsetZ)
    / GYRO_LSB_PER_DPS;

  accelMag =
    sqrtf(
      accelX_g * accelX_g +
      accelY_g * accelY_g +
      accelZ_g * accelZ_g
    );

  gyroMag =
    sqrtf(
      gyroX_dps * gyroX_dps +
      gyroY_dps * gyroY_dps +
      gyroZ_dps * gyroZ_dps
    );

  return true;
}

// ========================================================================
// BLE INITIALIZATION
// ========================================================================

void bleInit() {

  Serial.println();
  Serial.println(
    "[BLE] Starting BLE..."
  );

  BLEDevice::init(
    DEVICE_NAME
  );

  pServer =
    BLEDevice::createServer();

  pServer->setCallbacks(
    new ServerCallbacks()
  );

  BLEService* pService =
    pServer->createService(
      SERVICE_UUID
    );

  // ----------------------------------------------------------------------
  // TELEMETRY
  // ----------------------------------------------------------------------

  pTelemetryChar =
    pService->createCharacteristic(
      CHAR_TELEMETRY_UUID,
      BLECharacteristic::PROPERTY_NOTIFY
    );

  pTelemetryChar->addDescriptor(
    new BLE2902()
  );

  // ----------------------------------------------------------------------
  // CRASH
  // ----------------------------------------------------------------------

  pCrashChar =
    pService->createCharacteristic(
      CHAR_CRASH_UUID,
      BLECharacteristic::PROPERTY_NOTIFY |
      BLECharacteristic::PROPERTY_READ
    );

  pCrashChar->addDescriptor(
    new BLE2902()
  );

  uint8_t initialCrash = 0;

  pCrashChar->setValue(
    &initialCrash,
    1
  );

  // ----------------------------------------------------------------------
  // STATUS
  // ----------------------------------------------------------------------

  pStatusChar =
    pService->createCharacteristic(
      CHAR_STATUS_UUID,
      BLECharacteristic::PROPERTY_READ
    );

  pStatusChar->setValue(
    "ResQRide Helmet v1.0 | Ready | 100Hz"
  );

  pService->start();

  BLEAdvertising* advertising =
    BLEDevice::getAdvertising();

  advertising->addServiceUUID(
    SERVICE_UUID
  );

  advertising->setScanResponse(
    true
  );

  advertising->setMinPreferred(
    0x06
  );

  advertising->setMinPreferred(
    0x12
  );

  BLEDevice::startAdvertising();

  Serial.println(
    "[BLE] BLE started successfully."
  );

  Serial.print(
    "[BLE] Device: "
  );

  Serial.println(
    DEVICE_NAME
  );

  Serial.print(
    "[BLE] Service: "
  );

  Serial.println(
    SERVICE_UUID
  );

  Serial.println(
    "[BLE] Advertising..."
  );
}

// ========================================================================
// BLE TELEMETRY
// ========================================================================

void bleSendTelemetry() {

  if (
    !deviceConnected
  ) {

    return;
  }

  float payload[6] = {

    accelX_g,
    accelY_g,
    accelZ_g,

    gyroX_dps,
    gyroY_dps,
    gyroZ_dps
  };

  pTelemetryChar->setValue(
    (uint8_t*)payload,
    sizeof(payload)
  );

  pTelemetryChar->notify();
}

// ========================================================================
// BLE CRASH ALERT
// ========================================================================

void bleSendCrashAlert(
  bool crash
) {

  uint8_t value =
    crash ? 0x01 : 0x00;

  pCrashChar->setValue(
    &value,
    1
  );

  if (
    deviceConnected
  ) {

    pCrashChar->notify();
  }

  Serial.printf(
    "[BLE] Crash event: %s\n",
    crash
      ? "CRASH"
      : "CANCELLED"
  );
}

// ========================================================================
// BUZZER START
// ========================================================================

void buzzerStart() {

  buzzerStartTime =
    millis();

  buzzerActive = true;

  bool attached =
    ledcAttach(
      BUZZER_PIN,
      BUZZER_FREQ_HZ,
      BUZZER_RESOLUTION
    );

  if (!attached) {

    Serial.println(
      "[BUZZER] ERROR: LEDC attach failed!"
    );

    buzzerActive = false;

    return;
  }

  ledcWrite(
    BUZZER_PIN,
    128
  );

  Serial.println(
    "[BUZZER] ALERT SOUNDING!"
  );

  Serial.println(
    "[BUZZER] Press cancel button."
  );
}

// ========================================================================
// BUZZER STOP
// ========================================================================

void buzzerStop() {

  if (
    buzzerActive
  ) {

    ledcWrite(
      BUZZER_PIN,
      0
    );

    ledcDetach(
      BUZZER_PIN
    );
  }

  buzzerActive = false;

  digitalWrite(
    BUZZER_PIN,
    LOW
  );

  Serial.println(
    "[BUZZER] Silenced."
  );
}

// ========================================================================
// BUZZER UPDATE
// ========================================================================

void buzzerUpdate() {

  if (
    !buzzerActive
  ) {

    return;
  }

  if (
    millis() - buzzerStartTime
    >= BUZZER_DURATION_MS
  ) {

    buzzerStop();

    Serial.println(
      "[BUZZER] 15-second timeout."
    );
  }
}

// ========================================================================
// CRASH DETECTION
// ========================================================================

void checkForCrash() {

  if (
    crashDetected
  ) {

    return;
  }

  bool accelTriggered =
    accelMag >= ACCEL_THRESHOLD_G;

  bool gyroTriggered =
    gyroMag >= GYRO_THRESHOLD_DPS;

  if (
    accelTriggered &&
    gyroTriggered
  ) {

    sustainedHitCount++;

    Serial.printf(
      "[CRASH] Threshold sample %d/%d | "
      "Accel=%.2fg Gyro=%.1fdps\n",
      sustainedHitCount,
      SUSTAINED_COUNT,
      accelMag,
      gyroMag
    );

    if (
      sustainedHitCount >=
      SUSTAINED_COUNT
    ) {

      crashDetected = true;

      Serial.println();
      Serial.println(
        "========================================"
      );

      Serial.println(
        "       !!! CRASH DETECTED !!!"
      );

      Serial.println(
        "========================================"
      );

      Serial.printf(
        "Acceleration: %.2f g\n",
        accelMag
      );

      Serial.printf(
        "Gyroscope: %.1f deg/s\n",
        gyroMag
      );

      Serial.println(
        "Alert activated."
      );

      Serial.println(
        "========================================"
      );

      buzzerStart();

      bleSendCrashAlert(
        true
      );
    }

  } else {

    sustainedHitCount = 0;
  }
}

// ========================================================================
// CANCEL BUTTON
// ========================================================================

void handleCancelButton() {

  if (
    digitalRead(
      CANCEL_BTN_PIN
    ) != LOW
  ) {

    return;
  }

  if (
    millis() - lastButtonPress
    < DEBOUNCE_MS
  ) {

    return;
  }

  lastButtonPress =
    millis();

  if (
    crashDetected
  ) {

    Serial.println();
    Serial.println(
      "[CANCEL] Rider cancelled alert."
    );

    crashDetected = false;

    sustainedHitCount = 0;

    buzzerStop();

    bleSendCrashAlert(
      false
    );

  } else {

    Serial.println(
      "[BTN] Button pressed."
    );
  }
}

// ========================================================================
// LED MANAGEMENT
// ========================================================================

void updateLEDs() {

  // Power LED

  digitalWrite(
    LED_POWER_PIN,
    HIGH
  );

  // BLE LED

  if (
    deviceConnected
  ) {

    digitalWrite(
      LED_BLE_PIN,
      HIGH
    );

  } else {

    if (
      millis() - lastBleBlink
      >= BLE_BLINK_INTERVAL
    ) {

      lastBleBlink =
        millis();

      bleLedState =
        !bleLedState;

      digitalWrite(
        LED_BLE_PIN,
        bleLedState
      );
    }
  }
}

// ========================================================================
// SERIAL LIVE DATA
// ========================================================================

void printLiveData() {

  static int counter = 0;

  counter++;

  if (
    counter < 50
  ) {

    return;
  }

  counter = 0;

  Serial.printf(
    "[IMU] "
    "A X=%+5.2f "
    "Y=%+5.2f "
    "Z=%+5.2f "
    "|A|=%5.2fg  "
    "G X=%+6.1f "
    "Y=%+6.1f "
    "Z=%+6.1f "
    "|G|=%6.1f dps  "
    "BLE=%s "
    "CRASH=%s\n",

    accelX_g,
    accelY_g,
    accelZ_g,
    accelMag,

    gyroX_dps,
    gyroY_dps,
    gyroZ_dps,
    gyroMag,

    deviceConnected
      ? "CONNECTED"
      : "ADVERTISING",

    crashDetected
      ? "YES"
      : "NO"
  );
}

// ========================================================================
// SETUP
// ========================================================================

void setup() {

  Serial.begin(
    115200
  );

  delay(2000);

  Serial.println();
  Serial.println(
    "=============================================="
  );

  Serial.println(
    "      ResQRide Smart Helmet v1.0"
  );

  Serial.println(
    "      ESP32 + MPU6050 + BLE"
  );

  Serial.println(
    "      100Hz | 4g + 300dps"
  );

  Serial.println(
    "=============================================="
  );

  // ----------------------------------------------------------------------
  // PINS
  // ----------------------------------------------------------------------

  pinMode(
    LED_POWER_PIN,
    OUTPUT
  );

  pinMode(
    LED_BLE_PIN,
    OUTPUT
  );

  pinMode(
    BUZZER_PIN,
    OUTPUT
  );

  pinMode(
    CANCEL_BTN_PIN,
    INPUT_PULLUP
  );

  digitalWrite(
    LED_POWER_PIN,
    HIGH
  );

  digitalWrite(
    LED_BLE_PIN,
    LOW
  );

  digitalWrite(
    BUZZER_PIN,
    LOW
  );

  // ----------------------------------------------------------------------
  // I2C
  // ----------------------------------------------------------------------

  Serial.println();
  Serial.println(
    "[I2C] Starting I2C..."
  );

  Wire.begin(
    SDA_PIN,
    SCL_PIN
  );

  Wire.setClock(
    400000
  );

  Serial.println(
    "[I2C] SDA = GPIO 21"
  );

  Serial.println(
    "[I2C] SCL = GPIO 22"
  );

  // ----------------------------------------------------------------------
  // MPU6050
  // ----------------------------------------------------------------------

  if (
    !mpuInit()
  ) {

    Serial.println();
    Serial.println(
      "[FATAL] MPU6050 not found."
    );

    Serial.println(
      "[FATAL] Check wiring."
    );

    while (true) {

      digitalWrite(
        LED_POWER_PIN,
        !digitalRead(
          LED_POWER_PIN
        )
      );

      delay(250);
    }
  }

  // ----------------------------------------------------------------------
  // CALIBRATION
  // ----------------------------------------------------------------------

  mpuCalibrate();

  // ----------------------------------------------------------------------
  // BUZZER TEST
  // ----------------------------------------------------------------------

  Serial.println();
  Serial.println(
    "[BOOT] Testing buzzer..."
  );

  bool buzzerAttached =
    ledcAttach(
      BUZZER_PIN,
      BUZZER_FREQ_HZ,
      BUZZER_RESOLUTION
    );

  if (
    buzzerAttached
  ) {

    ledcWrite(
      BUZZER_PIN,
      128
    );

    delay(200);

    ledcWrite(
      BUZZER_PIN,
      0
    );

    ledcDetach(
      BUZZER_PIN
    );

    digitalWrite(
      BUZZER_PIN,
      LOW
    );

    Serial.println(
      "[BOOT] Buzzer OK."
    );

  } else {

    Serial.println(
      "[BOOT] WARNING: Buzzer failed."
    );
  }

  // ----------------------------------------------------------------------
  // BLE
  // ----------------------------------------------------------------------

  bleInit();

  // ----------------------------------------------------------------------
  // START SAMPLING
  // ----------------------------------------------------------------------

  lastSampleTime =
    micros();

  Serial.println();
  Serial.println(
    "=============================================="
  );

  Serial.println(
    "[READY] HELMET ARMED"
  );

  Serial.println(
    "[READY] Monitoring at 100Hz."
  );

  Serial.println(
    "[READY] Crash threshold: 4g + 300dps"
  );

  Serial.println(
    "[READY] Waiting for phone..."
  );

  Serial.println(
    "=============================================="
  );

  Serial.println();
}

// ========================================================================
// LOOP
// ========================================================================

void loop() {

  unsigned long now =
    micros();

  if (
    now - lastSampleTime
    < SAMPLE_INTERVAL_US
  ) {

    return;
  }

  lastSampleTime =
    now;

  // Read IMU

  if (
    !mpuReadCalibrated()
  ) {

    static unsigned long lastError = 0;

    if (
      millis() - lastError
      > 1000
    ) {

      lastError =
        millis();

      Serial.println(
        "[MPU6050] WARNING: Read failed."
      );
    }

    return;
  }

  // Crash detection

  checkForCrash();

  // BLE telemetry

  bleSendTelemetry();

  // Cancel button

  handleCancelButton();

  // Buzzer

  buzzerUpdate();

  // LEDs

  updateLEDs();

  // Serial

  printLiveData();
}
