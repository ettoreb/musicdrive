package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import coil3.compose.AsyncImage
import com.ettore.musicdrive.data.drive.DriveAlbum

const val UNKNOWN_ARTIST = "Unknown Artist"

data class ArtistSummary(val name: String, val albums: List<DriveAlbum>)

/** Groups by [DriveAlbum.artistHint] (the album folder's parent folder name); albums without one land in "Unknown Artist". */
fun List<DriveAlbum>.groupByArtist(): List<ArtistSummary> =
    groupBy { it.artistHint ?: UNKNOWN_ARTIST }
        .map { (name, albums) -> ArtistSummary(name, albums) }
        .sortedBy { it.name }

@Composable
fun ArtistListScreen(
    artists: List<ArtistSummary>,
    onArtistClick: (ArtistSummary) -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No artists found in this folder.")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GRID_TILE_MIN_SIZE),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(GRID_SPACING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        items(artists, key = { it.name }) { artist ->
            ArtistGridItem(artist = artist, onClick = { onArtistClick(artist) }, resolveArt = resolveArt)
        }
    }
}

@Composable
private fun ArtistGridItem(artist: ArtistSummary, onClick: () -> Unit, resolveArt: suspend (DriveAlbum) -> Any?) {
    // No per-artist artwork concept exists - stand in with the first album's cover, like most music apps do.
    val representativeAlbum = artist.albums.firstOrNull()
    var art by remember(artist.name) { mutableStateOf<Any?>(null) }
    LaunchedEffect(artist.name) { art = representativeAlbum?.let { resolveArt(it) } }

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(placeholderColorFor(artist.name)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    artist.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                )
            }
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
