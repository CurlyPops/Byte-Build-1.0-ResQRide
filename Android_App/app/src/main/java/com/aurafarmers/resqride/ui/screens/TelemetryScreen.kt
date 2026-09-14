package com.aurafarmers.resqride.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.data.model.ImuReading
import com.aurafarmers.resqride.ui.theme.*
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.FileWriter

@Composable
fun TelemetryScreen(
    liveImuStream: SharedFlow<ImuReading>,
    onCalibrateClicked: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var latestReading by remember { mutableStateOf(ImuReading(0f, 0f, 0f, 0f, 0f, 0f)) }

    // 1D-CNN Recorder state
    var isRecording by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf("0: Normal Riding") }
    val recordedSamples = remember { mutableStateListOf<Pair<ImuReading, String>>() }

    // Listen to live IMU stream
    LaunchedEffect(liveImuStream) {
        liveImuStream.collect { reading ->
            latestReading = reading
            if (isRecording) {
                recordedSamples.add(reading to selectedLabel)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ESP32 Sensor Telemetry",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "MPU-6050 6-Axis Accelerometer & Gyroscope",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCalibrateClicked) {
                Icon(Icons.Default.Refresh, contentDescription = "Calibrate", tint = EmeraldPrimary)
            }
        }

        // 1. Resultant G-Force Gauge Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "NET IMPACT G-FORCE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )

                val gForce = latestReading.resultantAccelG
                val gForceColor = if (gForce > 3.0f) EmergencyRed else if (gForce > 1.8f) WarningAmber else EmeraldPrimary

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format("%.2f g", gForce),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = gForceColor
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Linear G-force indicator bar (0 to 5g)
                LinearProgressIndicator(
                    progress = { (gForce / 5.0f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = gForceColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.0g (Rest)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Threshold: 3.5g", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmergencyRed)
                    Text("5.0g", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 2. 6-Axis Raw Values (Accelerometer & Gyroscope)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Accelerometer (Linear Acceleration in g)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AxisBox(label = "X-Axis", value = String.format("%.3f g", latestReading.ax), color = EmeraldPrimary)
                    AxisBox(label = "Y-Axis", value = String.format("%.3f g", latestReading.ay), color = TealAccent)
                    AxisBox(label = "Z-Axis", value = String.format("%.3f g", latestReading.az), color = MintSoft)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                Text(
                    text = "Gyroscope (Angular Velocity in °/s)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AxisBox(label = "Roll (Gx)", value = String.format("%.1f °/s", latestReading.gx), color = EmeraldPrimary)
                    AxisBox(label = "Pitch (Gy)", value = String.format("%.1f °/s", latestReading.gy), color = TealAccent)
                    AxisBox(label = "Yaw (Gz)", value = String.format("%.1f °/s", latestReading.gz), color = MintSoft)
                }
            }
        }

        // 3. 1D-CNN Dataset Recording Suite (For Phase 2 ML Training)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ModelTraining, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "1D-CNN Training Dataset Recorder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Record labelled IMU samples from the helmet to train the 1D-CNN crash classification model.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Label selector chips
                val labels = listOf("Normal Riding", "Pothole / Bump", "Sudden Brake", "Helmet Drop", "Crash Simulation")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Select Activity Class:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    labels.forEach { label ->
                        FilterChip(
                            selected = selectedLabel == label,
                            onClick = { selectedLabel = label },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Recorder Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            isRecording = !isRecording
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) EmergencyRed else EmeraldPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRecording) "Stop Logging" else "Record Samples")
                    }

                    if (recordedSamples.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                saveSamplesToCsv(context, recordedSamples)
                                recordedSamples.clear()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export CSV (${recordedSamples.size})", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun AxisBox(label: String, value: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun saveSamplesToCsv(context: Context, samples: List<Pair<ImuReading, String>>) {
    try {
        val file = File(context.filesDir, "imu_dataset_${System.currentTimeMillis()}.csv")
        FileWriter(file).use { writer ->
            writer.append("timestamp,ax,ay,az,gx,gy,gz,label\n")
            samples.forEach { (imu, label) ->
                writer.append("${imu.timestamp},${imu.ax},${imu.ay},${imu.az},${imu.gx},${imu.gy},${imu.gz},$label\n")
            }
        }
        Toast.makeText(context, "Saved ${samples.size} samples to ${file.name}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to export: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
