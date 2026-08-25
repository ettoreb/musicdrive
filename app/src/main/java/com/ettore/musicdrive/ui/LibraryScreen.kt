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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ettore.musicdrive.data.local.AlbumSortMode

fun List<DriveAlbum>.sortedByMode(mode: AlbumSortMode, yearOf: (DriveAlbum) -> Int? = { null }): List<DriveAlbum> = when (mode) {
    AlbumSortMode.NAME -> sortedBy { it.name }
    AlbumSortMode.TRACK_COUNT -> sortedByDescending { it.tracks.size }
    // Unresolved years (still being looked up) sort to the end rather than the top.
    AlbumSortMode.YEAR -> sortedWith(compareByDescending<DriveAlbum> { yearOf(it) ?: Int.MIN_VALUE }.thenBy { it.name })
    // Mainly useful on the flat Albums grid (which spans every artist) - a no-op within a
    // single artist's own album grid, same as it is for an ArtistAlbums-scoped NAME/YEAR sort.
    AlbumSortMode.ARTIST_NAME -> sortedWith(compareBy({ it.artistHint ?: "" }, { it.name }))
}

@Composable
fun LibraryScreen(
    albums: List<DriveAlbum>,
    onAlbumClick: (DriveAlbum) -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No albums found in this folder.")
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
        items(albums, key = { it.id }) { album ->
            AlbumGridItem(album = album, onClick = { onAlbumClick(album) }, resolveArt = resolveArt)
        }
    }
}

@Composable
private fun AlbumGridItem(album: DriveAlbum, onClick: () -> Unit, resolveArt: suspend (DriveAlbum) -> Any?) {
    var art by remember(album.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(album.id) { art = resolveArt(album) }

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
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
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                )
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
