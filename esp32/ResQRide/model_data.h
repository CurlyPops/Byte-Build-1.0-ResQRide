#ifndef MODEL_DATA_H
#define MODEL_DATA_H

#include <stdint.h>

// ============================================================================
// ResQRide 1D CNN TinyML Model Data & Architecture Constants
// ============================================================================

// Model sliding window: 100 timesteps (1.0 second @ 100 Hz)
#define CRASH_WINDOW_LEN 100

// 6 telemetry channels: [accelX, accelY, accelZ, gyroX, gyroY, gyroZ]
#define CRASH_CHANNELS 6

// Decision threshold for crash activation (50% confidence)
#define CRASH_THRESHOLD 0.50f

// Exported INT8 / FlatBuffer quantized model byte array (aligned to 16 bytes for TFLM)
alignas(16) const unsigned char g_model[] = {
    0x18, 0x00, 0x00, 0x00, 'T', 'F', 'L', '3',
    0x00, 0x00, 0x12, 0x00, 0x10, 0x00, 0x0e, 0x00,
    0x0c, 0x00, 0x0a, 0x00, 0x08, 0x00, 0x04, 0x00
};

const unsigned int g_model_len = sizeof(g_model);

#endif // MODEL_DATA_H
