package com.ettore.musicdrive.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.playback.driveMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * PARTIAL (some tracks completed, none actively in flight - e.g. one track
 * was downloaded standalone before the album download was ever tapped) is
 * kept distinct from DOWNLOADING (something actively queued/downloading
 * right now): tapping the album button must resume/finish a PARTIAL album,
 * not cancel it, while a genuinely in-flight DOWNLOADING album is fine to
 * cancel on tap - conflating the two made "download album" cancel and
 * delete an already-completed track the moment any other track in that
 * album had been downloaded individually beforehand.
 */
enum class AlbumDownloadState { NONE, PARTIAL, DOWNLOADING, COMPLETE }

fun albumDownloadState(album: DriveAlbum, downloads: Map<String, Download>): AlbumDownloadState {
    if (album.tracks.isEmpty()) return AlbumDownloadState.NONE
    val states = album.tracks.map { downloads[it.id]?.state }
    return when {
        states.all { it == Download.STATE_COMPLETED } -> AlbumDownloadState.COMPLETE
        states.any { it == Download.STATE_DOWNLOADING || it == Download.STATE_QUEUED || it == Download.STATE_RESTARTING } ->
            AlbumDownloadState.DOWNLOADING
        states.any { it == Download.STATE_COMPLETED } -> AlbumDownloadState.PARTIAL
        else -> AlbumDownloadState.NONE
    }
}

/**
 * Mirrors Media3's DownloadManager state into a Compose-friendly StateFlow,
 * and drives it through DownloadService.sendAddDownload/sendRemoveDownload so
 * downloads are queued in the service (surviving the app being closed), not
 * just held in this in-memory tracker. Album membership rides along in
 * DownloadRequest.data (the album's Drive folder id, UTF-8) rather than a
 * separate Room table, since DownloadManager already persists each request's
 * full byte payload in its own index.
 */
@UnstableApi
class DownloadTracker(
    private val context: Context,
    private val downloadManager: DownloadManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads

    init {
        scope.launch(Dispatchers.IO) {
            val initial = buildMap {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) put(cursor.download.request.id, cursor.download)
                }
            }
            _downloads.update { initial }
        }
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                _downloads.update { it + (download.request.id to download) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _downloads.update { it - download.request.id }
            }
        })
    }

    fun downloadTrack(track: DriveAudioFile, albumId: String) {
        val request = DownloadRequest.Builder(track.id, Uri.parse(driveMediaUri(track.id)))
            .setData(albumId.toByteArray(Charsets.UTF_8))
            .build()
        DownloadService.sendAddDownload(context, MusicDownloadService::class.java, request, false)
    }

    fun downloadAlbum(album: DriveAlbum) {
        album.tracks.forEach { downloadTrack(it, album.id) }
    }

    fun removeTrack(trackId: String) {
        DownloadService.sendRemoveDownload(context, MusicDownloadService::class.java, trackId, false)
    }

    fun removeAlbum(album: DriveAlbum) {
        album.tracks.forEach { removeTrack(it.id) }
    }

    /**
     * Clears every current download regardless of which album it belongs to - doesn't need to
     * resolve album metadata, so it still works for a download left over from a since-changed
     * library root that removeAlbum couldn't reach (its DriveAlbum no longer exists to iterate).
     */
    fun removeAll() {
        downloads.value.keys.forEach { removeTrack(it) }
    }
}
