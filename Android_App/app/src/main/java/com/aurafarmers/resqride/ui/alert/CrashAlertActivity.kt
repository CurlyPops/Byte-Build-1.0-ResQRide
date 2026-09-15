package com.aurafarmers.resqride.ui.alert

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.ResQRideApplication
import com.aurafarmers.resqride.sos.CrashAlertManager
import com.aurafarmers.resqride.ui.theme.EmeraldPrimary
import com.aurafarmers.resqride.ui.theme.EmergencyRed
import com.aurafarmers.resqride.ui.theme.EmergencyRedDark
import com.aurafarmers.resqride.ui.theme.ResQRideTheme

class CrashAlertActivity : ComponentActivity() {

    private lateinit var crashAlertManager: CrashAlertManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashAlertManager = CrashAlertManager.getInstance(this)
        turnScreenOnAndDismissKeyguard()

        setContent {
            ResQRideTheme {
                val remainingSeconds by crashAlertManager.remainingSeconds.collectAsState()
                val isAlertActive by crashAlertManager.isAlertActive.collectAsState()
                val isSosDispatched by crashAlertManager.isSosDispatched.collectAsState()
                val nearbyHospitals by crashAlertManager.nearbyHospitals.collectAsState()

                val prefs = ResQRideApplication.instance.preferences
                val contacts by prefs.contacts.collectAsState()
                val userProfile by prefs.userProfile.collectAsState()
                val primaryContact = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()

                // Auto-close when both alert and SOS are inactive
                LaunchedEffect(isAlertActive, isSosDispatched) {
                    if (!isAlertActive && !isSosDispatched) {
                        finish()
                    }
                }

                if (isSosDispatched) {
                    ActiveSosCallContent(
                        primaryContactName = primaryContact?.name ?: "Emergency Contact",
                        primaryContactPhone = primaryContact?.phone ?: "",
                        onCallClicked = {
                            val targetPhone = if (primaryContact != null && primaryContact.phone.isNotBlank()) {
                                primaryContact.phone
                            } else {
                                "112"
                            }
                            com.aurafarmers.resqride.sos.VoiceCallDispatcher.launchPhoneCall(this@CrashAlertActivity, targetPhone)
                        },
                        onRepeatVoiceClicked = {
                            crashAlertManager.voiceCallDispatcher.forceSpeakerphone()
                            crashAlertManager.voiceCallDispatcher.speakEmergencyMessage(
                                userProfile,
                                "${crashAlertManager.currentCrashLat}, ${crashAlertManager.currentCrashLon}"
                            )
                            Toast.makeText(this@CrashAlertActivity, "Playing emergency alert on speaker...", Toast.LENGTH_SHORT).show()
                        },
                        onSpeakerphoneClicked = {
                            crashAlertManager.voiceCallDispatcher.forceSpeakerphone()
                            Toast.makeText(this@CrashAlertActivity, "Speakerphone forced to maximum volume", Toast.LENGTH_SHORT).show()
                        },
                        onSendSmsFallbackClicked = {
                            if (primaryContact != null) {
                                val msg = crashAlertManager.smsDispatcher.buildEmergencyMessage(
                                    userProfile,
                                    crashAlertManager.currentCrashLat,
                                    crashAlertManager.currentCrashLon,
                                    nearbyHospitals
                                )
                                crashAlertManager.smsDispatcher.launchSmsFallback(primaryContact, msg)
                            } else {
                                Toast.makeText(this@CrashAlertActivity, "No emergency contact configured", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismissClicked = {
                            crashAlertManager.dismissSos()
                            finish()
                        }
                    )
                } else {
                    CrashAlertScreenContent(
                        remainingSeconds = remainingSeconds,
                        nearbyHospitalsCount = nearbyHospitals.size,
                        onCancelClicked = {
                            crashAlertManager.cancelAlert()
                            finish()
                        },
                        onSosNowClicked = {
                            crashAlertManager.dispatchEmergencySos()
                        }
                    )
                }
            }
        }
    }

    private fun turnScreenOnAndDismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}

@Composable
fun ActiveSosCallContent(
    primaryContactName: String,
    primaryContactPhone: String,
    onCallClicked: () -> Unit,
    onRepeatVoiceClicked: () -> Unit,
    onSpeakerphoneClicked: () -> Unit,
    onSendSmsFallbackClicked: () -> Unit,
    onDismissClicked: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_call")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "call_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1E1B4B),
                        Color(0xFF1E293B),
                        Color(0xFF0F172A)
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Calling Status Card
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .background(EmeraldPrimary.copy(alpha = 0.2f), shape = CircleShape)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(70.dp)
                            .background(EmeraldPrimary, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneInTalk,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "EMERGENCY CALL IN PROGRESS",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldPrimary,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = primaryContactName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                if (primaryContactPhone.isNotBlank()) {
                    Text(
                        text = primaryContactPhone,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Voice Alert Info Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.9f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Automated Voice Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The emergency announcement is looping automatically through the speakerphone every 7 seconds so your contact hears the alert as soon as they answer.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }

            // In-Call Assistance Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button: Call Contact Immediately
                Button(
                    onClick = onCallClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Icon(imageVector = Icons.Default.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (primaryContactPhone.isNotBlank()) "Call $primaryContactName" else "Call 112 (Emergency)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                // Button: Repeat Voice Alert Now
                OutlinedButton(
                    onClick = onRepeatVoiceClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimary)
                ) {
                    Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Repeat Voice Alert Now",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                // Button: Ensure Speakerphone
                OutlinedButton(
                    onClick = onSpeakerphoneClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                ) {
                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ensure Speakerphone at Max Volume", fontWeight = FontWeight.SemiBold)
                }

                // Button: Send SMS Fallback
                OutlinedButton(
                    onClick = onSendSmsFallbackClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA7F3D0))
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Emergency SMS via Messages App", fontWeight = FontWeight.SemiBold)
                }

                // Button: End / Dismiss
                TextButton(
                    onClick = onDismissClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "Dismiss / Stop Alert Loop",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
fun CrashAlertScreenContent(
    remainingSeconds: Int,
    nearbyHospitalsCount: Int,
    onCancelClicked: () -> Unit,
    onSosNowClicked: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF450A0A),
                        Color(0xFF1E1E1E),
                        Color(0xFF0F172A)
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Emergency Banner
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = EmergencyRed.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = EmergencyRed,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "POTENTIAL IMPACT DETECTED",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // Pulsing Countdown Circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(240.dp)
                    .scale(scale)
                    .background(EmergencyRedDark.copy(alpha = 0.4f), shape = CircleShape)
                    .padding(16.dp)
                    .background(EmergencyRed, shape = CircleShape)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$remainingSeconds",
                        fontSize = 76.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "SECONDS REMAINING",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            // Explanatory Information
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Are you OK?",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "If you don't cancel, an emergency SMS with your live GPS location and nearby hospitals will be dispatched, followed by an automated AI voice call.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
                if (nearbyHospitalsCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Identified $nearbyHospitalsCount nearby hospitals within 5km.",
                        fontSize = 12.sp,
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Large "I'm OK - Cancel" Button
                Button(
                    onClick = onCancelClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "I'M OK — CANCEL ALARM",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Instant SOS Dispatch Button
                OutlinedButton(
                    onClick = onSosNowClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
                ) {
                    Text(
                        text = "DISPATCH SOS NOW",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
