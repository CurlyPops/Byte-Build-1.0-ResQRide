package com.aurafarmers.resqride.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aurafarmers.resqride.data.model.FamilyCircle
import com.aurafarmers.resqride.data.model.FamilyMemberLocation
import com.aurafarmers.resqride.data.model.UserProfile
import com.aurafarmers.resqride.family.FamilyManager
import com.aurafarmers.resqride.ui.theme.*

@Composable
fun FamilyScreen(userProfile: UserProfile) {
    val context = LocalContext.current
    val familyManager = remember { FamilyManager.getInstance(context) }
    val familyCircle by familyManager.currentCircle.collectAsState()

    var showJoinDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var joinCodeInput by remember { mutableStateOf("") }
    var newCircleNameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Family Safety Circle",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Real-time location & crash alerts for your loved ones",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val circle = familyCircle
        if (circle == null) {
            // EMPTY STATE: No fake family members!
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "No Family Group Joined",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Connect with your family members to see their live location during rides and receive instant notifications if a crash is detected.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Create Circle")
                        }

                        OutlinedButton(
                            onClick = { showJoinDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Join Code")
                        }
                    }
                }
            }
        } else {
            // ACTIVE CIRCLE DISPLAY
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = circle.circleName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Invite Code (Share with family):",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 6-8 Character Code Badge
                        Surface(
                            color = EmeraldPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = circle.inviteCode,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = EmeraldDark,
                                letterSpacing = 2.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Family Code", circle.inviteCode))
                                Toast.makeText(context, "Code copied: ${circle.inviteCode}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Code", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { familyManager.leaveCircle() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Leave Circle", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Section Title: Live Members
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Circle Members (${circle.members.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                TextButton(onClick = { showJoinDialog = true }) {
                    Text("Join Another", fontSize = 12.sp, color = TealAccent)
                }
            }

            // Member Cards List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(circle.members) { member ->
                    FamilyMemberCard(member = member)
                }
            }
        }
    }

    // Join Circle Dialog
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join a Family Circle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the 6 or 8 character sequence provided by your family member:")
                    OutlinedTextField(
                        value = joinCodeInput,
                        onValueChange = { if (it.length <= 8) joinCodeInput = it.uppercase() },
                        label = { Text("Invite Code (e.g. SAFE9X2P)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val userName = userProfile.fullName.ifBlank { "You" }
                        val success = familyManager.joinCircleWithCode(joinCodeInput, userName, userProfile.phone)
                        if (success) {
                            Toast.makeText(context, "Joined circle successfully!", Toast.LENGTH_SHORT).show()
                            showJoinDialog = false
                            joinCodeInput = ""
                        } else {
                            Toast.makeText(context, "Invalid 6-8 character code", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Join")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create Circle Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Family Circle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Give your family safety group a name:")
                    OutlinedTextField(
                        value = newCircleNameInput,
                        onValueChange = { newCircleNameInput = it },
                        label = { Text("Circle Name (e.g. My Family)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCircleNameInput.isNotBlank()) {
                            val userName = userProfile.fullName.ifBlank { "You" }
                            familyManager.createNewCircle(newCircleNameInput, userName, userProfile.phone)
                            Toast.makeText(context, "New circle created!", Toast.LENGTH_SHORT).show()
                            showCreateDialog = false
                            newCircleNameInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FamilyMemberCard(member: FamilyMemberLocation) {
    val context = LocalContext.current

    val cardBg = if (member.hasCrashed) {
        EmergencyRed.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status Avatar
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (member.hasCrashed) EmergencyRed
                            else if (member.isRiding) EmeraldPrimary
                            else Color.Gray.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (member.hasCrashed) Icons.Default.Warning
                        else if (member.isRiding) Icons.Default.TwoWheeler
                        else Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = member.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Live Ride Status / Crash Status
                    if (member.hasCrashed) {
                        Text(
                            text = "CRASH DETECTED • NEEDS ASSISTANCE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EmergencyRed
                        )
                    } else if (member.isRiding) {
                        Text(
                            text = "Active Ride • ${member.speedKmh} km/h • Bat: ${member.batteryPercent}%",
                            fontSize = 12.sp,
                            color = EmeraldDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Parked / Idle • Bat: ${member.batteryPercent}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (member.latitude != 0.0 || member.longitude != 0.0) {
                        Text(
                            text = "${String.format("%.4f", member.latitude)}, ${String.format("%.4f", member.longitude)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Direct Phone Call Action
                if (member.phone.isNotBlank()) {
                    IconButton(
                        onClick = {
                            com.aurafarmers.resqride.sos.VoiceCallDispatcher.launchPhoneCall(context, member.phone)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call member",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Map navigation action
                if (member.latitude != 0.0 || member.longitude != 0.0) {
                    IconButton(
                        onClick = {
                            val mapUri = Uri.parse("geo:${member.latitude},${member.longitude}?q=${member.latitude},${member.longitude}(${member.name})")
                            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri)
                            context.startActivity(mapIntent)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Directions,
                            contentDescription = "Navigate to member",
                            tint = if (member.hasCrashed) EmergencyRed else TealAccent
                        )
                    }
                }
            }
        }
    }
}
