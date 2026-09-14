package com.aurafarmers.resqride.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aurafarmers.resqride.ResQRideApplication
import com.aurafarmers.resqride.ble.BleManager
import com.aurafarmers.resqride.service.RideTrackingService
import com.aurafarmers.resqride.ui.auth.AuthScreen
import com.aurafarmers.resqride.ui.screens.*
import com.aurafarmers.resqride.ui.theme.EmeraldPrimary
import com.aurafarmers.resqride.ui.theme.EmergencyRed
import com.aurafarmers.resqride.ui.theme.ResQRideTheme
import com.aurafarmers.resqride.util.PermissionHelper

sealed class Screen(val title: String, val icon: ImageVector) {
    object Dashboard : Screen("Dashboard", Icons.Default.Dashboard)
    object Family : Screen("Family", Icons.Default.People)
    object Medical : Screen("Medical ID", Icons.Default.MedicalServices)
    object Telemetry : Screen("Telemetry", Icons.AutoMirrored.Filled.ShowChart)
    object Settings : Screen("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager

    private var onPermissionResultCallback: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onPermissionResultCallback?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)

        requestRequiredAppPermissions()

        setContent {
            val userPreferences = ResQRideApplication.instance.preferences
            val isLoggedIn by userPreferences.isLoggedIn.collectAsState()
            val themeMode by userPreferences.themeMode.collectAsState()
            val userProfile by userPreferences.userProfile.collectAsState()
            val contacts by userPreferences.contacts.collectAsState()

            val helmetStatus by bleManager.helmetStatus.collectAsState()
            val connectionStatus by bleManager.connectionStatus.collectAsState()
            val rideMetrics by RideTrackingService.rideMetrics.collectAsState()

            var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

            // Dynamic permission tracking
            var isSmsGranted by remember { mutableStateOf(PermissionHelper.isSmsPermissionGranted(this)) }
            var showRestrictedSettingsDialog by remember { mutableStateOf(false) }

            // Re-check permissions on resume (e.g. when returning from Android Settings)
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        isSmsGranted = PermissionHelper.isSmsPermissionGranted(this@MainActivity)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Hook callback after runtime request finishes
            onPermissionResultCallback = {
                val granted = PermissionHelper.isSmsPermissionGranted(this@MainActivity)
                isSmsGranted = granted
                if (!granted) {
                    showRestrictedSettingsDialog = true
                }
            }

            ResQRideTheme(themeMode = themeMode) {
                // Restricted settings guidance dialog
                if (showRestrictedSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showRestrictedSettingsDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = EmergencyRed,
                                modifier = Modifier.size(32.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "Allow SMS in Restricted Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Because this app was downloaded directly (sideloaded), Android has temporarily restricted SMS permissions.",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Text(
                                    text = "To allow emergency crash SMS to be sent automatically:",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "1. Tap 'Open App Settings' below.\n" +
                                            "2. In the top-right corner, tap the 3 dots (⋮).\n" +
                                            "3. Tap 'Allow restricted settings' and verify your screen lock.\n" +
                                            "4. Go into Permissions > SMS > Allow.",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showRestrictedSettingsDialog = false
                                    PermissionHelper.openAppDetailsSettings(this@MainActivity)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Text("Open App Settings")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestrictedSettingsDialog = false }) {
                                Text("Later")
                            }
                        }
                    )
                }

                // Check if user is logged in & onboarding completed
                if (!isLoggedIn || !userProfile.isProfileComplete) {
                    AuthScreen(
                        userPreferences = userPreferences,
                        onAuthCompleted = {
                            currentScreen = Screen.Dashboard
                        }
                    )
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                val items = listOf(
                                    Screen.Dashboard,
                                    Screen.Family,
                                    Screen.Medical,
                                    Screen.Telemetry,
                                    Screen.Settings
                                )
                                items.forEach { screen ->
                                    val selected = currentScreen == screen
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title) },
                                        selected = selected,
                                        onClick = { currentScreen = screen },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = EmeraldPrimary,
                                            selectedTextColor = EmeraldPrimary,
                                            indicatorColor = EmeraldPrimary.copy(alpha = 0.15f)
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            // Persistent warning banner if SMS permission is blocked
                            if (!isSmsGranted) {
                                Surface(
                                    color = EmergencyRed.copy(alpha = 0.12f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showRestrictedSettingsDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = EmergencyRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "SMS SOS Blocked by Android",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = EmergencyRed
                                            )
                                            Text(
                                                text = "Tap to open settings & allow restricted settings",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "Fix",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = EmeraldPrimary
                                        )
                                    }
                                }
                            }

                            Surface(modifier = Modifier.fillMaxSize()) {
                                when (currentScreen) {
                                    Screen.Dashboard -> DashboardScreen(
                                        helmetStatus = helmetStatus,
                                        connectionStatus = connectionStatus,
                                        rideMetrics = rideMetrics,
                                        onConnectHelmetClicked = { bleManager.startScan() }
                                    )
                                    Screen.Family -> FamilyScreen(userProfile = userProfile)
                                    Screen.Medical -> MedicalScreen(
                                        userProfile = userProfile,
                                        onUpdateProfile = { updated -> userPreferences.saveUserProfile(updated) }
                                    )
                                    Screen.Telemetry -> TelemetryScreen(
                                        liveImuStream = bleManager.liveImuStream,
                                        onCalibrateClicked = {
                                            bleManager.emitMockImu(0.01f, -0.02f, 0.99f, 0.1f, 0.0f, -0.1f)
                                        }
                                    )
                                    Screen.Settings -> SettingsScreen(
                                        userPreferences = userPreferences,
                                        themeMode = themeMode,
                                        contacts = contacts,
                                        onThemeChanged = { userPreferences.setThemeMode(it) },
                                        onContactsUpdated = { userPreferences.saveContacts(it) },
                                        onLogoutClicked = { userPreferences.logout() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestRequiredAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
