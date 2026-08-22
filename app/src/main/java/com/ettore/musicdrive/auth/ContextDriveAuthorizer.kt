package com.ettore.musicdrive.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Silent-only Drive reauthorization for contexts without an Activity, e.g. a
 * foreground Service the OS restarts after process death, before MainActivity
 * has run again. Can mint a fresh access token once consent was already
 * granted in a prior session, but can't show a consent UI — if that's ever
 * needed (revoked access, first run), it fails and the user has to open the
 * app, same as if playback just hadn't started.
 */
class ContextDriveAuthorizer(context: Context) : DriveAuthorizer {

    private val authorizationClient = Identity.getAuthorizationClient(context)

    override suspend fun authorize(): DriveAuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()

        return suspendCancellableCoroutine { cont ->
            authorizationClient.authorize(request)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        cont.resume(DriveAuthResult.Failure("Interactive consent required; open the app"))
                    } else {
                        val token = result.accessToken
                        if (token != null) {
                            cont.resume(DriveAuthResult.Success(token))
                        } else {
                            cont.resume(DriveAuthResult.Failure("Authorized but no access token returned"))
                        }
                    }
                }
                .addOnFailureListener { e ->
                    cont.resume(DriveAuthResult.Failure("Authorization request failed: ${e.message}", e))
                }
        }
    }
}
