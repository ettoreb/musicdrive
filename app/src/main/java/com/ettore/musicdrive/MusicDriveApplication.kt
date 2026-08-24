package com.ettore.musicdrive

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.room.Room
import com.ettore.musicdrive.auth.ContextDriveAuthorizer
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.data.local.room.MusicDriveDatabase
import com.ettore.musicdrive.download.DownloadTracker
import com.ettore.musicdrive.playback.AdjustableLruEvictor
import com.ettore.musicdrive.playback.buildAuthenticatingHttpDataSourceFactory
import java.io.File
import java.util.concurrent.Executors
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

    /**
     * Set once a sign-in (silent or interactive) has succeeded this process.
     * MainActivity's composition can be torn down and recreated (e.g. the OS
     * reclaiming memory while backgrounded) without the process itself
     * dying; this flag survives that and lets it skip re-invoking Credential
     * Manager's getCredential() - which can flash a brief system UI even in
     * "silent" mode - when the session from earlier this process is still good.
     */
    var isSignedInThisProcess: Boolean = false

    /** Caches the Drive library index (albums + tracks) so it browses instantly on relaunch. */
    lateinit var database: MusicDriveDatabase
        private set

    /** Auto-managed, evictable cache for streamed playback. Bounded by a user-configurable size cap; evicts least-played tracks first (see AdjustableLruEvictor). */
    lateinit var streamingCache: SimpleCache
        private set

    /** Explicit per-song/per-album downloads. Never auto-evicted; written only via Media3 DownloadManager. */
    lateinit var downloadCache: SimpleCache
        private set

    /** Owns the download queue (persisted in its own table via [databaseProvider]) and survives the app being closed. */
    lateinit var downloadManager: DownloadManager
        private set

    /** Mirrors [downloadManager]'s state as Compose-friendly StateFlows; one instance for the whole process. */
    lateinit var downloadTracker: DownloadTracker
        private set

    /** Shared by both SimpleCache instances and the DownloadManager's own index, so there's one open db handle, not three. */
    private lateinit var databaseProvider: StandaloneDatabaseProvider

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
        databaseProvider = StandaloneDatabaseProvider(this)

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
        applicationScope.launch {
            database.playCountDao().observeAll().collect { counts ->
                streamingEvictor.playCounts = counts.associate { it.trackId to it.playCount }
            }
        }

        // filesDir, not cacheDir: downloads must survive OS cache-clearing, since they're permanent.
        downloadCache = SimpleCache(
            File(filesDir, "download_cache"),
            NoOpCacheEvictor(),
            databaseProvider,
        )
        downloadManager = DownloadManager(
            this,
            databaseProvider,
            downloadCache,
            buildAuthenticatingHttpDataSourceFactory(driveTokenProvider),
            Executors.newFixedThreadPool(3),
        )
        downloadTracker = DownloadTracker(this, downloadManager)
    }
}
