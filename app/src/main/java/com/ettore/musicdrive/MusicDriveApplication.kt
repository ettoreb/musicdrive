package com.ettore.musicdrive

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.playback.AdjustableLruEvictor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Two SEPARATE SimpleCache instances, each a singleton for the process
 * lifetime: creating two SimpleCache instances over the same directory
 * crashes, and recreating a player-bound cache elsewhere would too.
 */
@UnstableApi
class MusicDriveApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * Set by MainActivity once it constructs its (activity-bound) auth chain.
     * MusicPlaybackService reads this to authenticate Drive requests. Null
     * until MainActivity has run at least once in this process lifetime —
     * cold-starting playback purely from a notification/Android Auto after
     * process death isn't handled yet, see CLAUDE.md.
     */
    var driveTokenProvider: DriveTokenProvider? = null

    /** Auto-managed, evictable cache for streamed playback. Bounded by a user-configurable size cap. */
    lateinit var streamingCache: SimpleCache
        private set

    /** Explicit per-song/per-album downloads. Never auto-evicted; written only via Media3 DownloadManager. */
    lateinit var downloadCache: SimpleCache
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        val databaseProvider = StandaloneDatabaseProvider(this)

        val streamingEvictor = AdjustableLruEvictor(SettingsRepository.DEFAULT_CACHE_LIMIT_BYTES)
        streamingCache = SimpleCache(
            File(cacheDir, "streaming_cache"),
            streamingEvictor,
            databaseProvider,
        )
        applicationScope.launch {
            settingsRepository.cacheLimitBytes.collect { limitBytes ->
                streamingEvictor.maxBytes = limitBytes
            }
        }

        // filesDir, not cacheDir: downloads must survive OS cache-clearing, since they're permanent.
        downloadCache = SimpleCache(
            File(filesDir, "download_cache"),
            NoOpCacheEvictor(),
            databaseProvider,
        )
    }
}
