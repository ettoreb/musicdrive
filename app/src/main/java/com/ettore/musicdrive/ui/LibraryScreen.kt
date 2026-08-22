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
import kotlin.math.abs

/** Placeholder art until automatic album covers land: a colored tile with the album's initial. */
private val placeholderPalette = listOf(
    0xFF6750A4, 0xFF7D5260, 0xFF386A20, 0xFF984061, 0xFF31628E, 0xFF8C4A2F,
)

private fun placeholderColorFor(name: String): Color {
    val hash = name.hashCode()
    val index = (if (hash == Int.MIN_VALUE) 0 else abs(hash)) % placeholderPalette.size
    return Color(placeholderPalette[index])
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
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
        Text(
            "${album.tracks.size} track" + if (album.tracks.size == 1) "" else "s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
