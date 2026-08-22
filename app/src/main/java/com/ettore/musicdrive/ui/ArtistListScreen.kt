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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ettore.musicdrive.data.drive.DriveAlbum

private const val UNKNOWN_ARTIST = "Unknown Artist"

data class ArtistSummary(val name: String, val albums: List<DriveAlbum>) {
    val trackCount: Int get() = albums.sumOf { it.tracks.size }
}

/** Groups by [DriveAlbum.artistHint] (the album folder's parent folder name); albums without one land in "Unknown Artist". */
fun List<DriveAlbum>.groupByArtist(): List<ArtistSummary> =
    groupBy { it.artistHint ?: UNKNOWN_ARTIST }
        .map { (name, albums) -> ArtistSummary(name, albums) }
        .sortedBy { it.name }

@Composable
fun ArtistListScreen(
    artists: List<ArtistSummary>,
    onArtistClick: (ArtistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No artists found in this folder.")
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(artists, key = { it.name }) { artist ->
            ArtistRow(artist = artist, onClick = { onArtistClick(artist) })
        }
    }
}

@Composable
private fun ArtistRow(artist: ArtistSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(placeholderColorFor(artist.name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                artist.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val albumWord = if (artist.albums.size == 1) "album" else "albums"
            Text(
                "${artist.albums.size} $albumWord • ${artist.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
