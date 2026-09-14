package com.aurafarmers.resqride.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.data.model.UserProfile
import com.aurafarmers.resqride.medical.MedicalManager
import com.aurafarmers.resqride.medical.QrCodeGenerator
import com.aurafarmers.resqride.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicalScreen(
    userProfile: UserProfile,
    onUpdateProfile: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val medicalManager = remember { MedicalManager(context) }

    var showEditDialog by remember { mutableStateOf(false) }

    // Dynamic QR Bitmap pointing to public emergency profile
    val qrBitmap = remember(userProfile.publicEmergencyUrl) {
        QrCodeGenerator.generateQrBitmap(userProfile.publicEmergencyUrl, 512, 512)
    }

    // PDF Picker launcher
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: "prescription.pdf"
            onUpdateProfile(
                userProfile.copy(
                    prescriptionFileName = fileName,
                    prescriptionLocalUri = uri.toString()
                )
            )
            Toast.makeText(context, "Prescription PDF uploaded: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Emergency Medical ID",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            IconButton(onClick = { showEditDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Profile", tint = EmeraldPrimary)
            }
        }

        // 1. Personal & Blood Group Card
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userProfile.fullName.ifBlank { "Rider Name Not Set" },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Age: ${if (userProfile.age > 0) userProfile.age else "--"} • ${userProfile.gender.ifBlank { "Unspecified" }} • ${userProfile.phone.ifBlank { "No Phone" }}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Prominent Blood Group Badge
                    Surface(
                        color = EmergencyRed,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = userProfile.bloodGroup.ifBlank { "?" },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = "BLOOD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Known Allergies
                Text(
                    text = "Known Allergies:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (userProfile.allergies.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        userProfile.allergies.forEach { allergy ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(allergy, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = EmergencyRed.copy(alpha = 0.12f),
                                    labelColor = EmergencyRedDark
                                )
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No known drug allergies reported.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Chronic Conditions
                Text(
                    text = "Medical Conditions:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (userProfile.chronicConditions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        userProfile.chronicConditions.forEach { condition ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(condition, fontSize = 12.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = TealAccent.copy(alpha = 0.12f),
                                    labelColor = TealAccent
                                )
                            )
                        }
                    }
                } else {
                    Text(
                        text = "None reported.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Emergency Notes: ${userProfile.emergencyNotes.ifBlank { "None added." }}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Prescription PDF Card (Upload, Download, Print)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = EmergencyRed,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Prescription PDF Document",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = userProfile.prescriptionFileName ?: "No prescription uploaded yet",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (userProfile.prescriptionFileName != null) "Replace" else "Upload", fontSize = 12.sp)
                    }

                    if (userProfile.prescriptionFileName != null) {
                        Button(
                            onClick = {
                                val demoPdf = medicalManager.getOrCreateDemoPrescription()
                                medicalManager.printPrescription(demoPdf)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Print", fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = {
                                val demoPdf = medicalManager.getOrCreateDemoPrescription()
                                medicalManager.sharePrescription(demoPdf)
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = TealAccent)
                        }
                    }
                }
            }
        }

        // 3. Public Emergency Medical QR Code
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "First Responder Helmet QR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Public link scannable by paramedics & bystanders",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Display Generated QR Code Bitmap
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Medical QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    CircularProgressIndicator(color = EmeraldPrimary)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = userProfile.publicEmergencyUrl,
                    fontSize = 12.sp,
                    color = TealAccent,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(userProfile.publicEmergencyUrl))
                        context.startActivity(browserIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Preview Public Medical Webpage")
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Edit Profile Dialog
    if (showEditDialog) {
        var editName by remember { mutableStateOf(userProfile.fullName) }
        var editPhone by remember { mutableStateOf(userProfile.phone) }
        var editBlood by remember { mutableStateOf(userProfile.bloodGroup) }
        var editNotes by remember { mutableStateOf(userProfile.emergencyNotes) }

        val bloodGroups = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Medical Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Blood Group:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        bloodGroups.forEach { bg ->
                            FilterChip(
                                selected = editBlood == bg,
                                onClick = { editBlood = bg },
                                label = { Text(bg) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        label = { Text("Emergency Notes") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateProfile(
                            userProfile.copy(
                                fullName = editName.trim(),
                                phone = editPhone.trim(),
                                bloodGroup = editBlood.trim(),
                                emergencyNotes = editNotes.trim()
                            )
                        )
                        showEditDialog = false
                        Toast.makeText(context, "Medical details updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
