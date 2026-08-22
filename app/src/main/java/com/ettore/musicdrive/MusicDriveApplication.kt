package com.ettore.musicdrive

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.ettore.musicdrive.auth.ContextDriveAuthorizer
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.data.local.room.MusicDriveDatabase
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
     * Always available from process start, backed by a Context-only
     * ContextDriveAuthorizer so MusicPlaybackService can authenticate even if
     * the OS restarts it directly after process death (it returns
     * START_STICKY) without MainActivity having run first. MainActivity
     * upgrades this to an Activity-bound authorizer while it's alive, so
     * interactive consent can still be shown the rare times it's needed
     * (first run, revoked access), and swaps back on destroy.
     */
    lateinit var driveTokenProvider: DriveTokenProvider
        private set

    /** Caches the Drive library index (albums + tracks) so it browses instantly on relaunch. */
    lateinit var database: MusicDriveDatabase
        private set

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
        driveTokenProvider = DriveTokenProvider(ContextDriveAuthorizer(this))
        // Pre-release, local-only cache data (library index, lyrics) - fine to just rebuild
        // it rather than write real migrations while the schema is still actively changing.
        database = Room.databaseBuilder(this, MusicDriveDatabase::class.java, "musicdrive.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
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
