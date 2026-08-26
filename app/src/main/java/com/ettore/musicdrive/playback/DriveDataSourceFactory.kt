package com.ettore.musicdrive.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.ettore.musicdrive.MusicDriveApplication
import com.ettore.musicdrive.auth.DriveTokenProvider
import kotlinx.coroutines.runBlocking

/**
 * Wraps an HttpDataSource so every request carries a fresh Drive access
 * token, retrying once with a forced refresh on a 401 rather than failing
 * the whole playback. Blocking on the token fetch is safe here: Media3
 * calls DataSource.open() from its own loading thread, never the main thread.
 */
@UnstableApi
private class AuthenticatingHttpDataSource(
    private val wrapped: HttpDataSource,
    private val tokenProvider: DriveTokenProvider,
) : HttpDataSource by wrapped {

    override fun open(dataSpec: DataSpec): Long {
        val token = runBlocking { tokenProvider.getAccessToken() }.getOrThrow()
        wrapped.setRequestProperty("Authorization", "Bearer $token")
        return try {
            wrapped.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 401) {
                val refreshed = runBlocking { tokenProvider.getAccessToken(forceRefresh = true) }.getOrThrow()
                wrapped.setRequestProperty("Authorization", "Bearer $refreshed")
                wrapped.open(dataSpec)
            } else {
                throw e
            }
        }
    }
}

@UnstableApi
private class AuthenticatingHttpDataSourceFactory(
    private val tokenProvider: DriveTokenProvider,
    private val upstream: HttpDataSource.Factory = DefaultHttpDataSource.Factory(),
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        AuthenticatingHttpDataSource(upstream.createDataSource(), tokenProvider)
}

/** The direct-download URL for a Drive file, shared by playback and DownloadManager so their cache keys match. */
fun driveMediaUri(fileId: String): String = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

/** Shared with download/DownloadTracker.kt so DownloadManager's requests carry a fresh Bearer token too. */
@UnstableApi
fun buildAuthenticatingHttpDataSourceFactory(tokenProvider: DriveTokenProvider): DataSource.Factory =
    AuthenticatingHttpDataSourceFactory(tokenProvider)

/**
 * Playback source resolution order for a Drive (http/https) track: download cache -> streaming
 * cache -> network. Downloads are written only by Media3's DownloadManager, never by this
 * playback path (setCacheWriteDataSinkFactory(null) on the download cache layer), matching the
 * two-cache split in CLAUDE.md.
 */
@UnstableApi
private fun buildDriveDataSourceFactory(
    application: MusicDriveApplication,
    tokenProvider: DriveTokenProvider,
): DataSource.Factory {
    val authHttpFactory = AuthenticatingHttpDataSourceFactory(tokenProvider)

    val streamingCacheFactory = CacheDataSource.Factory()
        .setCache(application.streamingCache)
        .setUpstreamDataSourceFactory(authHttpFactory)

    return CacheDataSource.Factory()
        .setCache(application.downloadCache)
        .setUpstreamDataSourceFactory(streamingCacheFactory)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * The single DataSource.Factory the whole player uses for its entire lifetime (it must handle a
 * Drive album played now and a local album played later, in the same session - a per-item queue
 * built by MainActivity.playAlbum is always single-source, but the player itself isn't). Wraps
 * the Drive-specific cache chain above as the "base" of a DefaultDataSource.Factory:
 * DefaultDataSource already special-cases content:// -> ContentDataSource and file:// ->
 * FileDataSource, delegating only http(s):// to the wrapped base - so a local track's content://
 * URI never touches Drive auth or either SimpleCache at all, automatically, by URI scheme, with
 * no per-source branching needed anywhere else in playback (see docs/multi-source-plan.md §5).
 */
@UnstableApi
fun buildPlaybackDataSourceFactory(
    context: Context,
    application: MusicDriveApplication,
    tokenProvider: DriveTokenProvider,
): DataSource.Factory =
    DefaultDataSource.Factory(context, buildDriveDataSourceFactory(application, tokenProvider))
