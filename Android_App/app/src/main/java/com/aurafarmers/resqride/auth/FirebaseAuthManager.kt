package com.aurafarmers.resqride.auth

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

sealed class AuthResult {
    data class Success(val user: FirebaseUser) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class FirebaseAuthManager(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /**
     * Retrieves the current user's Firebase ID token for authenticating with the FastAPI backend.
     */
    suspend fun getIdToken(forceRefresh: Boolean = false): String? {
        return try {
            auth.currentUser?.getIdToken(forceRefresh)?.await()?.token
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Builds GoogleSignInClient.
     * Passes webClientId (from Firebase Console) to obtain the Google idToken.
     */
    fun getGoogleSignInClient(webClientId: String): GoogleSignInClient {
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (webClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(webClientId)
        }

        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    /**
     * Authenticates with Firebase using the Google idToken.
     */
    suspend fun signInWithGoogleToken(idToken: String): AuthResult {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Google authentication succeeded, but user profile was empty.")
            }
        } catch (e: Exception) {
            AuthResult.Error(parseAuthError(e))
        }
    }

    /**
     * Creates a new user with Email and Password using Firebase Auth.
     */
    suspend fun signUpWithEmail(email: String, password: String): AuthResult {
        return try {
            val authResult = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Sign-up succeeded, but failed to create user profile.")
            }
        } catch (e: Exception) {
            AuthResult.Error(parseAuthError(e))
        }
    }

    /**
     * Signs in an existing user with Email and Password using Firebase Auth.
     */
    suspend fun signInWithEmail(email: String, password: String): AuthResult {
        return try {
            val authResult = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = authResult.user
            if (user != null) {
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Login succeeded, but failed to retrieve user session.")
            }
        } catch (e: Exception) {
            AuthResult.Error(parseAuthError(e))
        }
    }

    /**
     * Signs out of Firebase and Google session.
     */
    fun signOut(webClientId: String = "") {
        auth.signOut()
        try {
            getGoogleSignInClient(webClientId).signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseAuthError(e: Exception): String {
        val msg = e.localizedMessage ?: e.message ?: "Authentication failed"
        return when {
            msg.contains("The email address is already in use", ignoreCase = true) ->
                "This email is already registered. Please log in instead."
            msg.contains("The email address is badly formatted", ignoreCase = true) ->
                "Invalid email format. Please enter a valid email address."
            msg.contains("There is no user record", ignoreCase = true) || msg.contains("user-not-found", ignoreCase = true) ->
                "No account found with this email. Please sign up first."
            msg.contains("password is invalid", ignoreCase = true) || msg.contains("wrong-password", ignoreCase = true) ->
                "Incorrect password. Please try again."
            msg.contains("network error", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                "Network connection error. Check your internet connection."
            msg.contains("API: 10", ignoreCase = true) || msg.contains("DEVELOPER_ERROR", ignoreCase = true) ->
                "Google Sign-In configuration error: SHA-1 fingerprint needs to be registered in Firebase Console."
            else -> msg
        }
    }
}
