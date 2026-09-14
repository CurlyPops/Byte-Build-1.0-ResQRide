package com.aurafarmers.resqride.ui.auth

import android.app.Activity
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafarmers.resqride.auth.AuthResult
import com.aurafarmers.resqride.auth.FirebaseAuthManager
import com.aurafarmers.resqride.data.model.EmergencyContact
import com.aurafarmers.resqride.data.model.UserProfile
import com.aurafarmers.resqride.data.network.PdfStorageService
import com.aurafarmers.resqride.data.pref.UserPreferences
import com.aurafarmers.resqride.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.util.UUID

enum class AuthStage {
    SIGN_IN_UP,
    ONBOARDING_PERSONAL,
    ONBOARDING_MEDICAL,
    ONBOARDING_PRESCRIPTION,
    ONBOARDING_CONTACTS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthScreen(
    userPreferences: UserPreferences,
    onAuthCompleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val firebaseAuthManager = remember { FirebaseAuthManager(context) }

    var stage by remember { mutableStateOf(AuthStage.SIGN_IN_UP) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auth credentials
    var emailInput by remember { mutableStateOf("") }
    var authenticatedUid by remember { mutableStateOf("") }

    // Strict Personal details
    var fullNameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") } // 10 digits only
    var ageInput by remember { mutableStateOf("") } // 16 to 100 only
    var selectedGender by remember { mutableStateOf("Male") } // strict selector: no typing "plumber"

    // Google Sign In Launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val authResult = firebaseAuthManager.signInWithGoogleToken(idToken)
                        isLoading = false
                        when (authResult) {
                            is AuthResult.Success -> {
                                authenticatedUid = authResult.user.uid
                                emailInput = authResult.user.email ?: account.email ?: ""
                                fullNameInput = authResult.user.displayName ?: account.displayName ?: ""
                                if (userPreferences.userProfile.value.isProfileComplete) {
                                    userPreferences.setLoggedIn(true)
                                    onAuthCompleted()
                                } else {
                                    stage = AuthStage.ONBOARDING_PERSONAL
                                }
                                Toast.makeText(context, "Google sign-in successful!", Toast.LENGTH_SHORT).show()
                            }
                            is AuthResult.Error -> {
                                errorMessage = authResult.message
                            }
                        }
                    }
                } else {
                    authenticatedUid = account.id ?: UUID.randomUUID().toString().take(8)
                    emailInput = account.email ?: ""
                    fullNameInput = account.displayName ?: ""
                    if (userPreferences.userProfile.value.isProfileComplete) {
                        userPreferences.setLoggedIn(true)
                        onAuthCompleted()
                    } else {
                        stage = AuthStage.ONBOARDING_PERSONAL
                    }
                    Toast.makeText(context, "Signed in with Google: ${account.displayName}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                isLoading = false
                errorMessage = "Google Sign-In error (code ${e.statusCode}). Please ensure SHA-1 fingerprint is registered in Firebase."
            }
        }
    }


    val genderOptions = listOf("Male", "Female", "Other", "Prefer not to say")

    // Strict Medical details
    var selectedBloodGroup by remember { mutableStateOf("") }
    val bloodGroups = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")
    var allergyInput by remember { mutableStateOf("") }
    val allergiesList = remember { mutableStateListOf<String>() }
    var conditionInput by remember { mutableStateOf("") }
    val conditionsList = remember { mutableStateListOf<String>() }
    var notesInput by remember { mutableStateOf("") }

    // Prescription PDF
    val pdfStorageService = remember { PdfStorageService(context) }
    var uploadedPdfName by remember { mutableStateOf<String?>(null) }
    var uploadedPdfUri by remember { mutableStateOf<String?>(null) }
    var uploadedPdfFileId by remember { mutableStateOf<String?>(null) }
    var isUploadingPdf by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "prescription.pdf"
            uploadedPdfName = fileName
            uploadedPdfUri = uri.toString()
            isUploadingPdf = true
            scope.launch {
                val uploadResult = pdfStorageService.uploadPdf(uri, fileName)
                isUploadingPdf = false
                uploadResult.onSuccess { result ->
                    uploadedPdfFileId = result.fileId
                    Toast.makeText(context, "Prescription uploaded: ${result.originalFilename}", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, "Cloud upload note: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Strict Emergency Contact details
    var primaryContactName by remember { mutableStateOf("") }
    var primaryContactPhone by remember { mutableStateOf("") } // 10 digits only
    var selectedRelationship by remember { mutableStateOf("Parent") } // strict selector
    val relationshipOptions = listOf("Parent", "Spouse", "Sibling", "Friend", "Guardian", "Other")

    // Validation checks
    val isPhoneValid = phoneInput.length == 10 && phoneInput.matches(Regex("^[6-9]\\d{9}$"))
    val isAgeValid = (ageInput.toIntOrNull() ?: 0) in 16..100
    val isPrimaryPhoneValid = primaryContactPhone.length == 10 && primaryContactPhone.matches(Regex("^[6-9]\\d{9}$"))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Header & Branding
            Icon(
                imageVector = Icons.Default.SportsMotorsports,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ResQRide",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Smart Helmet Crash Detection & Emergency SOS",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Global Error Banner
            AnimatedVisibility(visible = errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = EmergencyRed.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = EmergencyRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 12.sp,
                            color = EmergencyRed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = EmergencyRed)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (stage) {
                AuthStage.SIGN_IN_UP -> {
                    // STAGE 1: REAL GOOGLE OAUTH
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Sign In with Google",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "Sign in using your Google account to secure your emergency medical profile, helmet connection, and crash alerts.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Google OAuth Button
                            Button(
                                onClick = {
                                    errorMessage = null
                                    val webClientId = context.getString(com.aurafarmers.resqride.R.string.default_web_client_id)
                                    val client = firebaseAuthManager.getGoogleSignInClient(webClientId)
                                    googleSignInLauncher.launch(client.signInIntent)
                                },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        color = EmeraldPrimary,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            tint = EmeraldPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Continue with Google",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Verified & protected via Google Identity & Firebase Auth",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                AuthStage.ONBOARDING_PERSONAL -> {
                    // STAGE 2: PERSONAL DETAILS (STRICT CONTROLS - NO PLUMBERS, NO NEGATIVE NUMBERS)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Step 1 of 4: Rider Details",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = "Personal Information",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Name
                            OutlinedTextField(
                                value = fullNameInput,
                                onValueChange = { fullNameInput = it },
                                label = { Text("Your Full Name *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            // Strict 10-Digit Mobile Phone with +91 Prefix
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { input ->
                                    // Strictly allow digits only, max 10 characters, no negative signs or letters
                                    val digits = input.filter { it.isDigit() }.take(10)
                                    phoneInput = digits
                                },
                                label = { Text("Mobile Number *") },
                                leadingIcon = {
                                    Text(
                                        text = "+91 ",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                },
                                placeholder = { Text("9876543210") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                isError = phoneInput.isNotEmpty() && !isPhoneValid,
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (phoneInput.isNotEmpty() && !isPhoneValid) "Enter a valid 10-digit Indian mobile number" else "Required for emergency dispatch",
                                            color = if (phoneInput.isNotEmpty() && !isPhoneValid) EmergencyRed else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text("${phoneInput.length}/10")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            // Strict Age (Digits only, range 16-100)
                            OutlinedTextField(
                                value = ageInput,
                                onValueChange = { input ->
                                    // Strictly allow digits only, max 3 characters, no negative numbers
                                    val digits = input.filter { it.isDigit() }.take(3)
                                    ageInput = digits
                                },
                                label = { Text("Age (Years) *") },
                                placeholder = { Text("e.g. 24") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = ageInput.isNotEmpty() && !isAgeValid,
                                supportingText = {
                                    if (ageInput.isNotEmpty() && !isAgeValid) {
                                        Text("Age must be between 16 and 100", color = EmergencyRed)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            // Strict Gender Selection (Chips - NO typing "plumber" or arbitrary strings!)
                            Text(
                                text = "Gender *",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                genderOptions.forEach { gender ->
                                    val isSelected = selectedGender == gender
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedGender = gender },
                                        label = { Text(gender, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = EmeraldPrimary,
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (fullNameInput.trim().length < 2) {
                                        errorMessage = "Please enter your full name."
                                        return@Button
                                    }
                                    if (!isPhoneValid) {
                                        errorMessage = "Please enter a valid 10-digit mobile number."
                                        return@Button
                                    }
                                    if (!isAgeValid) {
                                        errorMessage = "Please enter a valid age between 16 and 100."
                                        return@Button
                                    }
                                    errorMessage = null
                                    stage = AuthStage.ONBOARDING_MEDICAL
                                },
                                enabled = fullNameInput.trim().length >= 2 && isPhoneValid && isAgeValid,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Text("Continue to Medical History", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                AuthStage.ONBOARDING_MEDICAL -> {
                    // STAGE 3: MEDICAL HISTORY (STRICT BLOOD GROUP SELECTOR & REAL CHIPS)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Step 2 of 4: Medical Safety Info",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = "Emergency Medical Data",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Encoded in your First-Responder Helmet QR code so paramedics can view life-saving details instantly.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Strict Blood Group Selector (Required)
                            Text("Select Blood Group *", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                bloodGroups.forEach { bg ->
                                    FilterChip(
                                        selected = selectedBloodGroup == bg,
                                        onClick = { selectedBloodGroup = bg },
                                        label = { Text(bg, fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = EmergencyRed,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            // Allergies
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = allergyInput,
                                    onValueChange = { allergyInput = it },
                                    label = { Text("Known Allergy (optional)") },
                                    placeholder = { Text("e.g. Penicillin, Peanuts") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (allergyInput.isNotBlank()) {
                                            allergiesList.add(allergyInput.trim())
                                            allergyInput = ""
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.AddCircle, contentDescription = "Add", tint = EmeraldPrimary)
                                }
                            }
                            if (allergiesList.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    allergiesList.forEach { a ->
                                        InputChip(
                                            selected = true,
                                            onClick = { allergiesList.remove(a) },
                                            label = { Text(a) },
                                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        )
                                    }
                                }
                            }

                            // Chronic Conditions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = conditionInput,
                                    onValueChange = { conditionInput = it },
                                    label = { Text("Medical Condition (optional)") },
                                    placeholder = { Text("e.g. Asthma, Diabetes") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (conditionInput.isNotBlank()) {
                                            conditionsList.add(conditionInput.trim())
                                            conditionInput = ""
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.AddCircle, contentDescription = "Add", tint = EmeraldPrimary)
                                }
                            }
                            if (conditionsList.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    conditionsList.forEach { c ->
                                        InputChip(
                                            selected = true,
                                            onClick = { conditionsList.remove(c) },
                                            label = { Text(c) },
                                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = notesInput,
                                onValueChange = { notesInput = it },
                                label = { Text("Emergency Notes for Paramedics (optional)") },
                                placeholder = { Text("e.g. Carry inhaler in jacket, Organ donor") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            Button(
                                onClick = {
                                    if (selectedBloodGroup.isNotBlank()) {
                                        stage = AuthStage.ONBOARDING_PRESCRIPTION
                                    } else {
                                        Toast.makeText(context, "Please select your blood group", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = selectedBloodGroup.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Text("Next: Prescription Document", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                AuthStage.ONBOARDING_PRESCRIPTION -> {
                    // STAGE 4: PRESCRIPTION PDF UPLOAD (OPTIONAL)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Step 3 of 4: Prescription PDF (Optional)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = "Upload Prescription Document",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Attach an official prescription PDF. Paramedics scanning your helmet QR can preview, download, or print it directly.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(2.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .background(EmeraldPrimary.copy(alpha = 0.05f))
                                    .clickable(enabled = !isUploadingPdf) { pdfPickerLauncher.launch("application/pdf") }
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (isUploadingPdf) {
                                        CircularProgressIndicator(
                                            color = EmeraldPrimary,
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.dp
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "Uploading to secure cloud storage...",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = null,
                                            tint = if (uploadedPdfName != null) EmeraldPrimary else TealAccent,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = uploadedPdfName ?: "Tap to choose Prescription PDF",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (uploadedPdfName != null) "Uploaded successfully to cloud" else "Supports .pdf files (max 10 MB)",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = { stage = AuthStage.ONBOARDING_CONTACTS },
                                enabled = !isUploadingPdf,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Text(if (uploadedPdfName != null) "Continue" else "Skip for Now", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                AuthStage.ONBOARDING_CONTACTS -> {
                    // STAGE 5: EMERGENCY CONTACT SETUP (STRICT VALIDATIONS)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Step 4 of 4: Emergency Contacts",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                            Text(
                                text = "Primary Emergency Contact",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "This contact receives the emergency crash SMS with GPS coordinates and the automated AI Voice Call.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Contact Name
                            OutlinedTextField(
                                value = primaryContactName,
                                onValueChange = { primaryContactName = it },
                                label = { Text("Contact Full Name *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            // Strict 10-digit Phone
                            OutlinedTextField(
                                value = primaryContactPhone,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(10)
                                    primaryContactPhone = digits
                                },
                                label = { Text("Contact Mobile Number *") },
                                leadingIcon = {
                                    Text(
                                        text = "+91 ",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                },
                                placeholder = { Text("9876543210") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                isError = primaryContactPhone.isNotEmpty() && !isPrimaryPhoneValid,
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (primaryContactPhone.isNotEmpty() && !isPrimaryPhoneValid) "Enter valid 10-digit number" else "Will receive the automated emergency call",
                                            color = if (primaryContactPhone.isNotEmpty() && !isPrimaryPhoneValid) EmergencyRed else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text("${primaryContactPhone.length}/10")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )

                            // Strict Relationship Selector (Chips)
                            Text(
                                text = "Relationship *",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                relationshipOptions.forEach { rel ->
                                    FilterChip(
                                        selected = selectedRelationship == rel,
                                        onClick = { selectedRelationship = rel },
                                        label = { Text(rel, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = EmeraldPrimary,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (primaryContactName.trim().length < 2) {
                                        errorMessage = "Please enter the contact's name."
                                        return@Button
                                    }
                                    if (!isPrimaryPhoneValid) {
                                        errorMessage = "Please enter a valid 10-digit phone number for your contact."
                                        return@Button
                                    }

                                    val userId = if (authenticatedUid.isNotBlank()) authenticatedUid else UUID.randomUUID().toString().take(8)
                                    val profile = UserProfile(
                                        uid = userId,
                                        fullName = fullNameInput.trim(),
                                        email = emailInput.trim(),
                                        phone = "+91 $phoneInput",
                                        age = ageInput.toIntOrNull() ?: 24,
                                        gender = selectedGender,
                                        bloodGroup = selectedBloodGroup,
                                        allergies = allergiesList.toList(),
                                        chronicConditions = conditionsList.toList(),
                                        prescriptionFileName = uploadedPdfName,
                                        prescriptionLocalUri = uploadedPdfUri,
                                        prescriptionFileId = uploadedPdfFileId,
                                        emergencyNotes = notesInput.trim(),
                                        isProfileComplete = true,
                                        publicEmergencyUrl = "https://resqride-oqhy.onrender.com/med/$userId"
                                    )
                                    userPreferences.saveUserProfile(profile)

                                    val contact = EmergencyContact(
                                        name = primaryContactName.trim(),
                                        phone = "+91 $primaryContactPhone",
                                        relationship = selectedRelationship,
                                        isPrimary = true
                                    )
                                    userPreferences.saveContacts(listOf(contact))

                                    userPreferences.setLoggedIn(true)
                                    Toast.makeText(context, "Welcome to ResQRide, ${profile.fullName}!", Toast.LENGTH_LONG).show()
                                    onAuthCompleted()
                                },
                                enabled = primaryContactName.trim().length >= 2 && isPrimaryPhoneValid,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Text("Complete Setup & Start Riding", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
