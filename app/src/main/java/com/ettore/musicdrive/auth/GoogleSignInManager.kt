package com.ettore.musicdrive.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
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

    /** No remembered/authorized Google account is available; caller should fall back to interactive sign-in. */
    data object NoCredential : SignInResult()

    data class Failure(val message: String, val cause: Throwable? = null) : SignInResult()
}

/**
 * Wraps Credential Manager sign-in. This only authenticates the user's identity;
 * it does NOT grant Drive access. See [DriveAuthorizationManager] for the
 * separate Drive scope consent step.
 */
class GoogleSignInManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    /** Attempts to sign in using a previously authorized account only, with no UI shown. */
    suspend fun signInSilently(): SignInResult = signIn(filterByAuthorizedAccounts = true, autoSelectEnabled = true)

    /** Shows the account picker / consent UI, offering any Google account on the device. */
    suspend fun signInInteractive(): SignInResult = signIn(filterByAuthorizedAccounts = false, autoSelectEnabled = false)

    /**
     * Clears Credential Manager's remembered sign-in state so a later [signInSilently] won't
     * silently succeed again - this does NOT revoke the Drive `drive.readonly` grant itself
     * (that's a separate consent tracked by [DriveAuthorizationManager]/Google's account
     * settings, not something Credential Manager owns), so re-signing in with the same account
     * later doesn't need the user to re-approve Drive access.
     */
    suspend fun signOut() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }

    private suspend fun signIn(filterByAuthorizedAccounts: Boolean, autoSelectEnabled: Boolean): SignInResult {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(autoSelectEnabled)
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
        } catch (e: NoCredentialException) {
            SignInResult.NoCredential
        } catch (e: GetCredentialException) {
            SignInResult.Failure("Sign-in failed: ${e.message}", e)
        } catch (e: GoogleIdTokenParsingException) {
            SignInResult.Failure("Failed to parse Google ID token: ${e.message}", e)
        }
    }
}
