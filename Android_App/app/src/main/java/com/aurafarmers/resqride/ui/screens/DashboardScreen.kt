package com.aurafarmers.resqride.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.ble.BleConnectionStatus
import com.aurafarmers.resqride.ble.HelmetStatus
import com.aurafarmers.resqride.data.model.RideMetrics
import com.aurafarmers.resqride.service.RideTrackingService
import com.aurafarmers.resqride.sos.CrashAlertManager
import com.aurafarmers.resqride.ui.theme.*

@Composable
fun DashboardScreen(
    helmetStatus: HelmetStatus,
    connectionStatus: BleConnectionStatus,
    rideMetrics: RideMetrics,
    onConnectHelmetClicked: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showSimDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ResQRide Helmet",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (rideMetrics.isRiding) "Ride Safety System Active" else "Ready to Ride",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rideMetrics.isRiding) EmeraldPrimary else TextSecondaryLight
                )
            }

            // Quick Status Badge
            Surface(
                color = if (helmetStatus.isConnected) EmeraldPrimary.copy(alpha = 0.15f) else Color.LightGray.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (helmetStatus.isConnected) EmeraldPrimary else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (helmetStatus.isConnected) "PAIRED" else "OFFLINE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (helmetStatus.isConnected) EmeraldDark else Color.Gray
                    )
                }
            }
        }

        // 1. Helmet Status Card
        HelmetStatusCard(
            helmetStatus = helmetStatus,
            connectionStatus = connectionStatus,
            onConnectClicked = onConnectHelmetClicked
        )

        // 2. Live Speed & Ride Metrics Display
        RideMetricsCard(rideMetrics = rideMetrics)

        // 3. Big "Start Ride" / "End Ride" Control Button
        RideControlButton(
            isRiding = rideMetrics.isRiding,
            onToggleRide = {
                if (rideMetrics.isRiding) {
                    RideTrackingService.stop(context)
                } else {
                    RideTrackingService.start(context)
                }
            }
        )

        // 4. Safe Crash Alert Simulation Card
        SimulationCard(
            onSimulateClicked = { showSimDialog = true }
        )

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Confirmation Dialog for Crash Simulation
    if (showSimDialog) {
        AlertDialog(
            onDismissRequest = { showSimDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber) },
            title = { Text("Simulate Crash Alert?") },
            text = {
                Text("This will activate the 20-second siren countdown and emergency lock-screen activity for safety testing. You can cancel it during the countdown before SMS is sent.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSimDialog = false
                        CrashAlertManager.getInstance(context).startCrashAlert(
                            latitude = rideMetrics.currentLatitude,
                            longitude = rideMetrics.currentLongitude
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text("Trigger Simulation")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSimDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HelmetStatusCard(
    helmetStatus: HelmetStatus,
    connectionStatus: BleConnectionStatus,
    onConnectClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SportsMotorsports,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Helmet Hardware Status",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!helmetStatus.isConnected) {
                    TextButton(onClick = onConnectClicked) {
                        Text(
                            text = if (connectionStatus == BleConnectionStatus.SCANNING) "Scanning..." else "Connect BLE",
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3-Column Metrics: Battery %, RSSI, Calibration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // Battery
                StatusItem(
                    icon = Icons.Default.BatteryChargingFull,
                    iconTint = if (helmetStatus.batteryPercent > 20) EmeraldPrimary else WarningAmber,
                    label = "Battery",
                    value = if (helmetStatus.isConnected) "${helmetStatus.batteryPercent}%" else "--"
                )

                // RSSI
                StatusItem(
                    icon = Icons.Default.BluetoothConnected,
                    iconTint = TealAccent,
                    label = "Signal",
                    value = if (helmetStatus.isConnected) "${helmetStatus.rssiDbm} dBm" else "--"
                )

                // Calibration
                StatusItem(
                    icon = Icons.Default.Tune,
                    iconTint = if (helmetStatus.isCalibrated) EmeraldPrimary else WarningAmber,
                    label = "Sensor",
                    value = if (helmetStatus.isConnected) "Calibrated" else "--"
                )
            }
        }
    }
}

@Composable
fun StatusItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RideMetricsCard(rideMetrics: RideMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CURRENT SPEED",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )

            // Speedometer Display
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.1f", rideMetrics.currentSpeedKmh),
                    fontSize = 58.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "km/h",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EmeraldPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // Duration & Distance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val hours = rideMetrics.durationSeconds / 3600
                    val minutes = (rideMetrics.durationSeconds % 3600) / 60
                    val seconds = rideMetrics.durationSeconds % 60
                    val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                    Text(text = timeFormatted, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = "Ride Duration", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format("%.2f km", rideMetrics.totalDistanceKm),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(text = "Distance", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun RideControlButton(
    isRiding: Boolean,
    onToggleRide: () -> Unit
) {
    val buttonBrush = if (isRiding) {
        Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFFEF4444)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF059669), Color(0xFF10B981), Color(0xFF34D399)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(buttonBrush)
            .clickable { onToggleRide() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isRiding) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isRiding) "END RIDE" else "START RIDE",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun SimulationCard(onSimulateClicked: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Crash Alert Test / Simulation",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Safely test the 20s countdown and SMS pipeline.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onSimulateClicked,
                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Test", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}
