package com.ettore.musicdrive.auth

import android.app.Activity
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

sealed class DriveAuthResult {
    data class Success(val accessToken: String) : DriveAuthResult()
    data class Failure(val message: String, val cause: Throwable? = null) : DriveAuthResult()
}

/**
 * Requests the Drive readonly scope via Identity.getAuthorizationClient. This is
 * separate from [GoogleSignInManager]: sign-in proves identity, authorization
 * grants API access and may require a one-time consent screen (IntentSender).
 *
 * Must be constructed in the activity's onCreate (before STARTED), because
 * registerForActivityResult requires it.
 */
class DriveAuthorizationManager(private val activity: ComponentActivity) {

    private val authorizationClient = Identity.getAuthorizationClient(activity)
    private var pendingContinuation: CancellableContinuation<DriveAuthResult>? = null

    private val consentLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val cont = pendingContinuation
            pendingContinuation = null
            if (cont == null || !cont.isActive) return@registerForActivityResult

            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    val authResult = authorizationClient.getAuthorizationResultFromIntent(result.data)
                    val token = authResult.accessToken
                    if (token != null) {
                        cont.resume(DriveAuthResult.Success(token))
                    } else {
                        cont.resume(DriveAuthResult.Failure("Consent granted but no access token returned"))
                    }
                } catch (e: ApiException) {
                    cont.resume(DriveAuthResult.Failure("Failed to extract authorization result: ${e.message}", e))
                }
            } else {
                cont.resume(DriveAuthResult.Failure("User declined Drive access"))
            }
        }

    /**
     * Requests (or silently renews) the drive.readonly access token. Suspends
     * until the user resolves the consent screen, if one is shown.
     */
    suspend fun authorize(): DriveAuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()

        return suspendCancellableCoroutine { cont ->
            pendingContinuation = cont
            authorizationClient.authorize(request)
                .addOnSuccessListener { result: AuthorizationResult ->
                    if (result.hasResolution()) {
                        val pendingIntent = result.pendingIntent
                        if (pendingIntent == null) {
                            pendingContinuation = null
                            cont.resume(DriveAuthResult.Failure("Resolution required but no pending intent"))
                            return@addOnSuccessListener
                        }
                        try {
                            consentLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            pendingContinuation = null
                            cont.resume(DriveAuthResult.Failure("Failed to launch consent screen: ${e.message}", e))
                        }
                    } else {
                        pendingContinuation = null
                        val token = result.accessToken
                        if (token != null) {
                            cont.resume(DriveAuthResult.Success(token))
                        } else {
                            cont.resume(DriveAuthResult.Failure("Authorized but no access token returned"))
                        }
                    }
                }
                .addOnFailureListener { e ->
                    pendingContinuation = null
                    cont.resume(DriveAuthResult.Failure("Authorization request failed: ${e.message}", e))
                }
        }
    }
}
