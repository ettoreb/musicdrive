package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
import coil3.compose.AsyncImage
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile

/** One most-played-songs grid tile: the track itself, plus which album/index to resume playback from. */
data class HomeGridItem(val album: DriveAlbum, val track: DriveAudioFile, val trackIndex: Int, val playCount: Int)

/** One most-played-artist tile. */
data class TopArtistItem(val artist: ArtistSummary, val playCount: Int)

@Composable
fun HomeScreen(
    topTracks: List<HomeGridItem>,
    topArtists: List<TopArtistItem>,
    likedSongsCount: Int,
    onTrackClick: (HomeGridItem) -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
    onLikedSongsClick: () -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    resolveArtistArt: suspend (String) -> Any?,
    modifier: Modifier = Modifier,
) {
    if (topTracks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Play some songs and your most-played tracks will show up here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
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
        item(span = { GridItemSpan(maxLineSpan) }) {
            LikedSongsCard(count = likedSongsCount, onClick = onLikedSongsClick)
        }

        if (topArtists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TopArtistsRow(artists = topArtists, onArtistClick = onArtistClick, resolveArtistArt = resolveArtistArt)
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Most played",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        items(topTracks, key = { it.track.id }) { item ->
            HomeGridTile(item = item, onClick = { onTrackClick(item) }, resolveArt = resolveArt)
        }
    }
}

@Composable
private fun LikedSongsCard(count: Int, onClick: () -> Unit) {
    if (count == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                "Liked Songs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Your $count most-played track" + if (count == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun TopArtistsRow(
    artists: List<TopArtistItem>,
    onArtistClick: (ArtistSummary) -> Unit,
    resolveArtistArt: suspend (String) -> Any?,
) {
    Column {
        Text(
            "Artists you've been playing",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(artists, key = { it.artist.name }) { item ->
                TopArtistTile(item = item, onClick = { onArtistClick(item.artist) }, resolveArtistArt = resolveArtistArt)
            }
        }
    }
}

@Composable
private fun TopArtistTile(item: TopArtistItem, onClick: () -> Unit, resolveArtistArt: suspend (String) -> Any?) {
    var art by remember(item.artist.name) { mutableStateOf<Any?>(null) }
    LaunchedEffect(item.artist.name) { art = resolveArtistArt(item.artist.name) }

    Column(
        modifier = Modifier.width(88.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(placeholderColorFor(item.artist.name)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = item.artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    item.artist.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
        }
        Text(
            item.artist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HomeGridTile(item: HomeGridItem, onClick: () -> Unit, resolveArt: suspend (DriveAlbum) -> Any?) {
    var art by remember(item.album.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(item.album.id) { art = resolveArt(item.album) }

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(placeholderColorFor(item.track.name)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = item.track.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    item.track.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                )
            }
        }
        Text(
            item.track.name.withoutAudioExtension(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            item.album.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
