package com.aurafarmers.resqride.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.pref.UserPreferences
import com.aurafarmers.resqride.ui.theme.*
import com.aurafarmers.resqride.util.PermissionHelper

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    userPreferences: UserPreferences,
    themeMode: ThemeMode,
    contacts: List<EmergencyContact>,
    onThemeChanged: (ThemeMode) -> Unit,
    onContactsUpdated: (List<EmergencyContact>) -> Unit,
    onLogoutClicked: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var countdownSeconds by remember { mutableStateOf(userPreferences.countdownSeconds.toFloat()) }
    var sensitivity by remember { mutableStateOf(userPreferences.crashSensitivity) }

    var showAddContactDialog by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var newContactRelation by remember { mutableStateOf("Family") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "App Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1. Appearance / Dark Mode Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (themeMode == ThemeMode.DARK) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = null,
                        tint = EmeraldPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Appearance & Dark Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.values().forEach { mode ->
                        val isSelected = themeMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { onThemeChanged(mode) },
                            label = {
                                Text(
                                    text = when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldPrimary,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Emergency Contacts Management (With Primary Selector for AI Voice Call)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContactPhone, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Emergency Contacts",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Primary contact receives the AI Voice Call",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = { showAddContactDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = EmeraldPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (contacts.isEmpty()) {
                    Text(
                        text = "No emergency contacts added yet. Tap '+' above to add your family or emergency contacts who will receive the crash alert and AI voice call.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    contacts.forEach { contact ->
                        ContactRow(
                            contact = contact,
                            onSetPrimary = {
                                val updated = contacts.map { it.copy(isPrimary = it.id == contact.id) }
                                onContactsUpdated(updated)
                            },
                            onDelete = {
                                val updated = contacts.filter { it.id != contact.id }
                                onContactsUpdated(updated)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // 3. Siren & Crash Sensitivity Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "False-Alarm Cancellation Window",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Time to cancel via phone screen or helmet switch before SMS & voice call are dispatched.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = countdownSeconds,
                        onValueChange = {
                            countdownSeconds = it
                            userPreferences.countdownSeconds = it.toInt()
                        },
                        valueRange = 10f..45f,
                        steps = 6,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = EmeraldPrimary, activeTrackColor = EmeraldPrimary)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${countdownSeconds.toInt()}s",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                Text(
                    text = "Crash Impact Sensitivity",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("LOW (4.5g)", "MEDIUM (3.5g)", "HIGH (2.5g)").forEach { level ->
                        val code = level.substringBefore(" ")
                        FilterChip(
                            selected = sensitivity == code,
                            onClick = {
                                sensitivity = code
                                userPreferences.crashSensitivity = code
                            },
                            label = { Text(level, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 4. Emergency SOS Permissions & Sideload Fix Card
        val isSmsGranted = PermissionHelper.isSmsPermissionGranted(context)
        val isCallGranted = PermissionHelper.isCallPermissionGranted(context)
        val isLocGranted = PermissionHelper.isFineLocationGranted(context)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = if (isSmsGranted) EmeraldPrimary else EmergencyRed
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Emergency SOS Permissions",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isSmsGranted) "All emergency channels ready" else "Action required: Sideload restriction active",
                            fontSize = 12.sp,
                            color = if (isSmsGranted) EmeraldPrimary else EmergencyRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PermissionStatusRow(
                        title = "Automatic Crash SMS Dispatch",
                        isGranted = isSmsGranted,
                        restrictedNote = "Blocked by Android"
                    )
                    PermissionStatusRow(
                        title = "Direct Emergency Phone Call",
                        isGranted = isCallGranted
                    )
                    PermissionStatusRow(
                        title = "High-Accuracy GPS Location",
                        isGranted = isLocGranted
                    )
                }

                if (!isSmsGranted) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = EmergencyRed.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "How to unblock on Android 13/14:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = EmergencyRed
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Tap the button below to open App Info.\n" +
                                        "2. Tap the 3 dots (⋮) in the top-right corner.\n" +
                                        "3. Select 'Allow restricted settings' & enter PIN.\n" +
                                        "4. Return to Permissions > SMS > Allow.",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { PermissionHelper.openAppDetailsSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSmsGranted) EmeraldPrimary.copy(alpha = 0.85f) else EmergencyRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSmsGranted) "Open App Settings" else "Open Settings to Allow SMS",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5. Account & Logout
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Account & Session",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onLogoutClicked,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Log Out / Reset Account", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Add Contact Dialog
    if (showAddContactDialog) {
        val relOptions = listOf("Parent", "Spouse", "Sibling", "Friend", "Guardian", "Other")
        val isNewPhoneValid = newContactPhone.length == 10 && newContactPhone.matches(Regex("^[6-9]\\d{9}$"))

        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add Emergency Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newContactName,
                        onValueChange = { newContactName = it },
                        label = { Text("Contact Full Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = newContactPhone,
                        onValueChange = { input ->
                            newContactPhone = input.filter { it.isDigit() }.take(10)
                        },
                        label = { Text("Mobile Number *") },
                        leadingIcon = {
                            Text("+91 ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                        },
                        placeholder = { Text("9876543210") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        ),
                        isError = newContactPhone.isNotEmpty() && !isNewPhoneValid,
                        supportingText = {
                            Text(
                                text = if (newContactPhone.isNotEmpty() && !isNewPhoneValid) "Enter valid 10-digit mobile" else "${newContactPhone.length}/10 digits",
                                color = if (newContactPhone.isNotEmpty() && !isNewPhoneValid) EmergencyRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text("Relationship *", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        relOptions.forEach { rel ->
                            FilterChip(
                                selected = newContactRelation == rel,
                                onClick = { newContactRelation = rel },
                                label = { Text(rel, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newContactName.trim().length >= 2 && isNewPhoneValid) {
                            val newContact = EmergencyContact(
                                name = newContactName.trim(),
                                phone = "+91 $newContactPhone",
                                relationship = newContactRelation.trim(),
                                isPrimary = contacts.isEmpty()
                            )
                            onContactsUpdated(contacts + newContact)
                            showAddContactDialog = false
                            newContactName = ""
                            newContactPhone = ""
                        }
                    },
                    enabled = newContactName.trim().length >= 2 && isNewPhoneValid,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Add Contact")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ContactRow(
    contact: EmergencyContact,
    onSetPrimary: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (contact.isPrimary) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = EmeraldPrimary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "PRIMARY (AI CALL)",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${contact.phone} • ${contact.relationship}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                if (!contact.isPrimary) {
                    TextButton(onClick = onSetPrimary) {
                        Text("Make Primary", fontSize = 11.sp, color = EmeraldPrimary)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    isGranted: Boolean,
    restrictedNote: String = "Not granted"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) EmeraldPrimary else EmergencyRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isGranted) "Enabled" else restrictedNote,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isGranted) EmeraldPrimary else EmergencyRed
            )
        }
    }
}

