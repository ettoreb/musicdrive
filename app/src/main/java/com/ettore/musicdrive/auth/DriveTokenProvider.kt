package com.ettore.musicdrive.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the Drive access token in memory and re-authorizes on demand.
 * Access tokens expire after ~1h; callers (e.g. the Drive HTTP data source used
 * by ExoPlayer) must call getAccessToken(forceRefresh = true) after a 401
 * instead of failing outright.
 */
class DriveTokenProvider(private val driveAuthorizationManager: DriveAuthorizationManager) {

    private val mutex = Mutex()
    @Volatile private var cachedToken: String? = null

    suspend fun getAccessToken(forceRefresh: Boolean = false): Result<String> = mutex.withLock {
        val cached = cachedToken
        if (!forceRefresh && cached != null) {
            return@withLock Result.success(cached)
        }
        when (val result = driveAuthorizationManager.authorize()) {
            is DriveAuthResult.Success -> {
                cachedToken = result.accessToken
                Result.success(result.accessToken)
            }
            is DriveAuthResult.Failure -> {
                cachedToken = null
                Result.failure(IllegalStateException(result.message, result.cause))
            }
        }
    }

    fun invalidate() {
        cachedToken = null
    }
}
