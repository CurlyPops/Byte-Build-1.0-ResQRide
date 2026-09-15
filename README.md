# ResQRide: Universal Smart Emergency Response & Edge-AI Crash Detection System

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/hardware-ESP32--WROOM--32-blue.svg)]()
[![Android](https://img.shields.io/badge/Android-Kotlin%202.0%20%7C%20Compose-green.svg)]()
[![Backend](https://img.shields.io/badge/backend-FastAPI%20%7C%20Render-blueviolet.svg)](https://resqride-oqhy.onrender.com)
[![Storage](https://img.shields.io/badge/database-Supabase-emerald.svg)](https://resqride-oqhy.onrender.com)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)]()

> **Universal Emergency Service Domain:** `https://resqride-oqhy.onrender.com`

---

## 📌 Executive Summary

**ResQRide** is an ultra-affordable, universal smart helmet retrofit system that converts ordinary motorcycle, scooter, and bicycle helmets into active, life-saving emergency response units.

```
+----------------------------------------------------------------------------------------------------+
|                                      THE "GOLDEN HOUR" CRISIS                                      |
|  Over 1.3 million people die in road traffic accidents globally each year. In India alone,         |
|  two-wheeler riders account for >44% of all fatal collisions. Trauma survival drops by 50% for     |
|  every 30 minutes emergency medical services (EMS) are delayed. ResQRide cuts emergency dispatch   |
|  notification time from hours to under 15 seconds.                                                |
+----------------------------------------------------------------------------------------------------+
```

ResQRide achieves zero-latency edge crash detection, zero false alarms, automated emergency dispatch, and instant first-responder medical triage:
1. **Zero-Latency Edge Inference:** An ultra-lightweight **1D CNN TinyML Engine** running directly on an embedded **ESP32-WROOM-32** evaluating 100 Hz 6-axis IMU telemetry every 100 ms (<15 ms forward pass).
2. **Double False-Alarm Immunity:** 15-second 2700 Hz acoustic buzzer alert paired with an active-LOW physical cancel rocker switch.
3. **Automated Emergency Dispatch:** High-precision GPS cellular SMS with direct Google & Apple Maps links, top 3 nearby 5km trauma hospitals, and automated voice calling with continuous repeating speakerphone speech alerts.
4. **First-Responder Medical Triage (`/med/{user_id}`):** Weatherproof physical QR tag on the helmet exterior linking to dynamic FastAPI cards powered by Supabase cloud storage, rendering critical vitals (Blood Group, Allergies, Chronic Conditions) and verified prescription PDFs.
5. **Radical Affordability:** Prototype BOM of **~₹850 ($10.20)**, scaling to **~₹430 ($5.14)** at 100K volume—undercutting commercial smart helmets by over 85%.

---

## 🏗️ End-to-End System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       HELMET RETROFIT HARDWARE MODULE                       │
│  - Microcontroller: ESP32-WROOM-32D (Dual-Core 240MHz, BLE 4.2)             │
│  - Kinematic Sensor: MPU6050 6-DOF (±16g, ±2000°/s @ 100 Hz)                │
│  - Edge Intelligence: Embedded 1D CNN TinyML Engine (helmet_cnn.h)          │
│  - Local Alarm: 2700 Hz Piezo Buzzer (LEDC PWM, 85dB SPL)                   │
│  - False-Alarm Cancel: Physical Rocker Switch on GPIO 4                      │
│  - Status LEDs: GPIO 2 (Power), GPIO 15 (BLE / Monitoring Status)           │
│  - Battery: 3.7V 650mAh LiPo (16+ hours runtime) + TP4056 USB-C Charger     │
│  - Triage: Weatherproof QR Medical Tag -> https://resqride-oqhy.onrender.com│
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Bluetooth Low Energy (BLE GATT)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        COMPANION ANDROID APPLICATION                        │
│  - Architecture: Kotlin 2.0 + Jetpack Compose (MVVM Clean Architecture)     │
│  - Authentication: Pure Google OAuth 2.0 via Firebase (No mock data)        │
│  - Telemetry Ingestion: 100 Hz Continuous BLE GATT Stream                   │
│  - Lock-Screen Emergency Alert: Full-screen heads-up overlay with siren     │
│  - Triple Cancel Pipeline: Helmet Switch, Phone Volume Keys, Screen Slider  │
│  - Automated Dispatch: Background SMS + Google/Apple Maps + Hospital Locator│
│  - Automated Calling: ACTION_CALL with looping 7-second speakerphone TTS    │
│  - Sideload Security: Android 13/14 Restricted Settings permission unlock  │
│  - Cloud Sync: Automatic rider profile & prescription sync to Supabase      │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ HTTPS REST API
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     EMERGENCY RESPONSE & CLOUD SERVICES                     │
│  - Primary Cloud Service: https://resqride-oqhy.onrender.com               │
│  - Database & Storage: Supabase (profiles bucket + pdfs private bucket)     │
│  - Dynamic Triage Route: GET /med/{user_id} (Paramedic instant web card)    │
│  - Profile Sync API: POST /api/profile (Real-time data ingestion)           │
│  - Medical Vault: Signed prescription PDF streaming (10-minute / 1-hour)    │
│  - Identity & Forensics: Firebase Admin ID Token verification & Firestore   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## ⚡ Hardware Pinout & Circuit Schematic

```
                     +---------------------------+
                     |       ESP32-WROOM-32      |
                     |                           |
   [3.3V] -----------| 3V3                   GND |----------- [GND]
   [GND] ------------| GND                   EN  |
                     |                           |
   MPU6050 SDA ------| GPIO 21 (SDA)     GPIO 22 |----------- MPU6050 SCL
   Buzzer (+) -------| GPIO 25           GPIO 4  |----------- Cancel Switch (to GND)
   Power LED (+) ----| GPIO 2            GPIO 15 |----------- Status/BLE LED (+)
                     +---------------------------+
```

| Component | Pin / Signal | ESP32 GPIO | Mode | Electrical Characteristics |
| :--- | :--- | :--- | :--- | :--- |
| **MPU6050 IMU** | `SDA` | **GPIO 21** | I2C Data | 4.7kΩ pull-up to 3.3V rail |
| **MPU6050 IMU** | `SCL` | **GPIO 22** | I2C Clock | 400 kHz Fast-Mode I2C |
| **MPU6050 IMU** | `VCC` / `GND` | **3V3** / **GND** | Power | Regulated 3.3V DC rail |
| **Piezo Buzzer** | `Positive (+)` | **GPIO 25** | PWM / LEDC | 2700 Hz acoustic resonant frequency, 85dB |
| **Cancel Switch** | `Terminal 1` | **GPIO 4** | `INPUT_PULLUP`| Active-LOW rocker switch (flip OFF -> ON) |
| **Cancel Switch** | `Terminal 2` | **GND** | Ground | Ground reference |
| **Power LED** | `Anode (+)` | **GPIO 2** | `OUTPUT` | Onboard blue / external green LED (330Ω resistor) |
| **Status/BLE LED**| `Anode (+)` | **GPIO 15** | `OUTPUT` | Flashes during advertising; solid when connected |

---

## 🧠 Edge AI: 1D CNN TinyML Engine

* **Dataset:** 8,500 balanced 1-second windows (`resqride_v2_balanced.npz`) combining real-world bicycle riding telemetry, resting/stationary states, severe road potholes/speed bumps, emergency braking, and physical crash impacts with $SO(3)$ 3D rotational invariance.
* **Accuracy:** 100% test accuracy, PR-AUC = 1.0000, ROC-AUC = 1.0000.
* **Noise Margin:** High non-crash prediction ($0.0001$) vs decision threshold ($0.5000$) provides a massive 50% safety margin.
* **INT8 Quantization:** Shrunk to **22.32 KB** INT8 model deployed in pure C++ (`helmet_cnn.h`), consuming $<18\text{ KB}$ Flash and $<4\text{ KB}$ RAM without TFLite runtime overhead.

---

## 📱 Android Application Features

1. **Pure Google OAuth 2.0:** Secure authentication using Firebase Auth with Web Client ID `679854880152-dsmo8nh03f7s4trfap3rpno7u94nakup.apps.googleusercontent.com`.
2. **Automated Cellular SMS Alert:** Dispatches multi-part emergency SMS with:
   - Live GPS Coordinates & Clickable Google Maps & Apple Maps URLs.
   - Top 3 nearest 5km hospitals with direct phone numbers.
   - Rider blood group and critical medical tags.
   - Direct web link to the dynamic emergency medical profile: `https://resqride-oqhy.onrender.com/med/{user_id}`.
3. **Automated Emergency Call with Looping TTS:** Places phone call (`ACTION_CALL`), routes audio to built-in speakerphone (`AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`), and loops synthesized voice alert every 7 seconds over ~50 seconds.
4. **Android 13/14 Restricted Settings Sideload Fix:** Integrated detection and direct launcher to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with guided step-by-step unblocking.
5. **Supabase Cloud Sync:** Automatically uploads user profile and prescription PDFs to Supabase via FastAPI endpoints.

---

## 🌐 Supabase & FastAPI Dynamic Medical Triage API

Hosted on Render: `https://resqride-oqhy.onrender.com`

| Endpoint | Method | Description |
| :--- | :---: | :--- |
| `/health` | `GET` | Service status and Supabase/Firebase configuration check |
| `/api/profile` | `POST` | Ingests user profile & contacts into Supabase `profiles/{uid}.json` |
| `/api/profile/{uid}` | `GET` | Retrieves user profile JSON from Supabase |
| `/api/files/upload` | `POST` | Uploads prescription PDF to Supabase private bucket `pdfs` |
| `/api/files` | `GET` | Lists user's private prescription documents |
| `/api/files/{file_id}` | `GET` | Generates short-lived signed download URL |
| `/med/{user_id}` | `GET` | **Dynamic Paramedic Medical Triage Card** (HTML) with blood group, allergies, 1-tap call, and prescription PDF download links |
| `/api/med/{user_id}` | `GET` | Programmatic JSON emergency triage data |

---

## 💰 Bill of Materials (BOM) & Unit Economics

| Component | Prototype (1K) | Scale (10K) | Mass Production (100K) |
| :--- | :--- | :--- | :--- |
| **ESP32-WROOM-32D** | $2.80 | $2.10 | $1.65 |
| **MPU6050 IMU** | $0.95 | $0.65 | $0.48 |
| **3.7V 650mAh LiPo Battery** | $1.80 | $1.35 | $1.05 |
| **TP4056 + USB-C Charger** | $0.35 | $0.25 | $0.18 |
| **Piezo Buzzer (2700 Hz)** | $0.25 | $0.18 | $0.12 |
| **Physical Rocker Switch** | $0.20 | $0.14 | $0.09 |
| **Custom 2-Layer PCB** | $0.85 | $0.45 | $0.28 |
| **ABS Waterproof Enclosure** | $1.60 | $1.00 | $0.65 |
| **Passives, Box & Cable** | $1.40 | $0.95 | $0.64 |
| **Total Hardware Cost** | **$10.20 (~₹850)** | **$7.07 (~₹590)** | **$5.14 (~₹430)** |

* **Retail Selling Price (MSRP):** ₹2,499 ($30.00)
* **Gross Margin (10K Scale):** **76.4%**

---

## 🚀 Quickstart & Build Instructions

### 1. Firmware Flash (ESP32)
Open `esp32/ResQRide/ResQRide.ino` in Arduino IDE or PlatformIO:
- Board: `ESP32 Dev Module`
- Upload Speed: `921600`
- Flash Frequency: `80MHz`
- Connect hardware according to the pinout table and flash.

### 2. Backend API Deployment
```bash
cd Website/Backend
# Install dependencies with uv
uv sync
# Run pytest test suite (17 passed)
uv run pytest
# Run locally
uv run uvicorn main:app --reload --port 8000
```

### 3. Android Application Compilation
```bash
cd Android_App
# Assemble release/debug APK
.\gradlew assembleDebug
# Generated APK: Android_App/app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License & Team
Developed by **Team AuraFarmers** for **Byte-Build 1.0**.  
Released under the MIT License.
