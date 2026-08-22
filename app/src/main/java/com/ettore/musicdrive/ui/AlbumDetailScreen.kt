package com.ettore.musicdrive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.download.AlbumDownloadState
import com.ettore.musicdrive.download.albumDownloadState

@Composable
fun AlbumDetailScreen(
    album: DriveAlbum,
    downloads: Map<String, Download>,
    onBack: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onDownloadTrack: (DriveAudioFile) -> Unit,
    onRemoveTrackDownload: (DriveAudioFile) -> Unit,
    onDownloadAlbum: () -> Unit,
    onRemoveAlbumDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = album.name, onBack = onBack) {
            AlbumDownloadButton(
                state = albumDownloadState(album, downloads),
                onDownload = onDownloadAlbum,
                onRemove = onRemoveAlbumDownload,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackClick(index) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        track.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TrackDownloadButton(
                        download = downloads[track.id],
                        onDownload = { onDownloadTrack(track) },
                        onRemove = { onRemoveTrackDownload(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumDownloadButton(state: AlbumDownloadState, onDownload: () -> Unit, onRemove: () -> Unit) {
    when (state) {
        AlbumDownloadState.NONE, AlbumDownloadState.PARTIAL -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "Download album")
        }
        AlbumDownloadState.DOWNLOADING -> IconButton(onClick = onRemove) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        AlbumDownloadState.COMPLETE -> IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Remove downloaded album",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TrackDownloadButton(download: Download?, onDownload: () -> Unit, onRemove: () -> Unit) {
    when (download?.state) {
        Download.STATE_COMPLETED -> IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Remove downloaded track",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> IconButton(onClick = onRemove) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "Download track")
        }
    }
}
