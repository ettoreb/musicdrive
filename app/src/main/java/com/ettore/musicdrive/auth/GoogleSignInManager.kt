package com.ettore.musicdrive.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.ettore.musicdrive.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

data class SignedInUser(
    val idToken: String,
    val email: String?,
    val displayName: String?,
)

sealed class SignInResult {
    data class Success(val user: SignedInUser) : SignInResult()
    data class Failure(val message: String, val cause: Throwable? = null) : SignInResult()
}

/**
 * Wraps Credential Manager sign-in. This only authenticates the user's identity;
 * it does NOT grant Drive access. See [DriveAuthorizationManager] for the
 * separate Drive scope consent step.
 */
class GoogleSignInManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): SignInResult {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val response = credentialManager.getCredential(context, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                SignInResult.Success(
                    SignedInUser(
                        idToken = googleIdTokenCredential.idToken,
                        email = googleIdTokenCredential.id,
                        displayName = googleIdTokenCredential.displayName,
                    )
                )
            } else {
                SignInResult.Failure("Unexpected credential type: ${credential.type}")
            }
        } catch (e: GetCredentialException) {
            SignInResult.Failure("Sign-in failed: ${e.message}", e)
        } catch (e: GoogleIdTokenParsingException) {
            SignInResult.Failure("Failed to parse Google ID token: ${e.message}", e)
        }
    }
}
