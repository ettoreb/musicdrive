package com.ettore.musicdrive.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.ettore.musicdrive.MainActivity
import com.ettore.musicdrive.MusicDriveApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@UnstableApi
class MusicPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    // Scoped to this service's own lifecycle (separate from MusicDriveApplication's
    // process-wide scope) so the browse-tree callback's coroutines are cancelled with it.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val app = application as MusicDriveApplication
        val dataSourceFactory = buildDriveDataSourceFactory(app, app.driveTokenProvider)
        // These are simple progressive HTTP audio streams, not adaptive (DASH/HLS) content, so
        // the defaults (tuned for adaptive streaming, ~2.5s/5s before playback starts) add
        // needless tap-to-audio latency. A much smaller pre-playback buffer is plenty here.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 15_000,
                /* maxBufferMs= */ 30_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000,
            )
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        // Powers Android Auto's "open app" affordance on the now-playing screen/notification.
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, MusicLibrarySessionCallback(app, serviceScope))
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
