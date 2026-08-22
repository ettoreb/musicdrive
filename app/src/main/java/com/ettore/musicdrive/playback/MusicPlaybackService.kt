package com.ettore.musicdrive.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ettore.musicdrive.MusicDriveApplication

@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        val app = application as MusicDriveApplication
        val tokenProvider = app.driveTokenProvider
            ?: run {
                // No auth chain yet (MainActivity hasn't run in this process). Nothing to play.
                stopSelf()
                return
            }

        val dataSourceFactory = buildDriveDataSourceFactory(app, tokenProvider)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
