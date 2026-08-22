package com.ettore.musicdrive.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the Drive access token in memory and re-authorizes on demand.
 * Access tokens expire after ~1h; callers (e.g. the Drive HTTP data source used
 * by ExoPlayer) must call getAccessToken(forceRefresh = true) after a 401
 * instead of failing outright.
 */
class DriveTokenProvider(@Volatile private var authorizer: DriveAuthorizer) {

    private val mutex = Mutex()
    @Volatile private var cachedToken: String? = null

    /**
     * Swaps the authorizer in place, e.g. MainActivity upgrading a
     * Context-only authorizer to an Activity-bound one (so interactive
     * consent can be shown if ever needed) while it's alive, and back down
     * when it's destroyed.
     */
    fun setAuthorizer(newAuthorizer: DriveAuthorizer) {
        authorizer = newAuthorizer
    }

    suspend fun getAccessToken(forceRefresh: Boolean = false): Result<String> = mutex.withLock {
        val cached = cachedToken
        if (!forceRefresh && cached != null) {
            return@withLock Result.success(cached)
        }
        when (val result = authorizer.authorize()) {
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
