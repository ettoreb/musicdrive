package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import coil3.compose.AsyncImage
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile

private data class TrackMatch(val album: DriveAlbum, val track: DriveAudioFile, val index: Int)

/**
 * Filters the already-loaded library in memory - the whole library is
 * already resident (LibraryScreen/HomeScreen work the same way), so a
 * separate search index or Drive query isn't needed for a personal-sized
 * collection.
 */
@Composable
fun SearchScreen(
    albums: List<DriveAlbum>,
    onAlbumClick: (DriveAlbum) -> Unit,
    onTrackClick: (album: DriveAlbum, index: Int) -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    val matchingAlbums = remember(query, albums) {
        if (query.isBlank()) {
            emptyList()
        } else {
            albums.filter {
                it.name.contains(query, ignoreCase = true) || it.artistHint?.contains(query, ignoreCase = true) == true
            }
        }
    }
    val matchingTracks = remember(query, albums) {
        if (query.isBlank()) {
            emptyList()
        } else {
            albums.flatMap { album ->
                album.tracks.mapIndexedNotNull { index, track ->
                    if (track.name.contains(query, ignoreCase = true)) TrackMatch(album, track, index) else null
                }
            }.take(50)
        }
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search albums, artists, songs") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        when {
            query.isBlank() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Search your library by song, album, or artist name.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            matchingAlbums.isEmpty() && matchingTracks.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No results for \"$query\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (matchingAlbums.isNotEmpty()) {
                    item {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(matchingAlbums, key = { "album-${it.id}" }) { album ->
                        AlbumResultRow(album = album, onClick = { onAlbumClick(album) }, resolveArt = resolveArt)
                    }
                }
                if (matchingTracks.isNotEmpty()) {
                    item {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(matchingTracks, key = { "track-${it.track.id}" }) { match ->
                        TrackResultRow(match = match, onClick = { onTrackClick(match.album, match.index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumResultRow(album: DriveAlbum, onClick: () -> Unit, resolveArt: suspend (DriveAlbum) -> Any?) {
    var art by remember(album.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(album.id) { art = resolveArt(album) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
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
                Text(album.name.take(1).uppercase(), color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.artistHint != null) {
                Text(
                    album.artistHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrackResultRow(match: TrackMatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                match.track.name.withoutAudioExtension(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                match.album.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
