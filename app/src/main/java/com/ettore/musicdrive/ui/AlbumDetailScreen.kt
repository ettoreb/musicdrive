package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import coil3.compose.AsyncImage
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.download.AlbumDownloadState
import com.ettore.musicdrive.download.albumDownloadState

@Composable
fun AlbumDetailScreen(
    album: DriveAlbum,
    downloads: Map<String, Download>,
    resolveArt: suspend (DriveAlbum) -> Any?,
    onBack: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onDownloadAlbum: () -> Unit,
    onRemoveAlbumDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var art by remember(album.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(album.id) { art = resolveArt(album) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "", onBack = onBack)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(placeholderColorFor(album.name)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            AsyncImage(
                                model = art,
                                contentDescription = album.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                album.name.take(1).uppercase(),
                                style = MaterialTheme.typography.displayLarge,
                                color = Color.White,
                            )
                        }
                    }

                    Text(
                        album.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (album.artistHint != null) {
                        Text(
                            album.artistHint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AlbumDownloadButton(
                        state = albumDownloadState(album, downloads),
                        onDownload = onDownloadAlbum,
                        onRemove = onRemoveAlbumDownload,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
            }

            itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackClick(index) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        track.name.withoutAudioExtension(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumDownloadButton(
    state: AlbumDownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AlbumDownloadState.NONE, AlbumDownloadState.PARTIAL -> Button(onClick = onDownload, modifier = modifier) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Download album")
        }
        AlbumDownloadState.DOWNLOADING -> Button(onClick = onRemove, modifier = modifier) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            Text("Downloading… tap to cancel")
        }
        AlbumDownloadState.COMPLETE -> Button(
            onClick = onRemove,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Filled.DownloadDone, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Downloaded · tap to remove")
        }
    }
}
