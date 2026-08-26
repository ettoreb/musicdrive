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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.ettore.musicdrive.data.TrackMatch
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.searchLibrary

/** One most-played-songs grid tile: the track itself, plus which album/index to resume playback from. */
data class HomeGridItem(val album: DriveAlbum, val track: DriveAudioFile, val trackIndex: Int, val playCount: Int)

/** One most-played-artist tile. */
data class TopArtistItem(val artist: ArtistSummary, val playCount: Int)

/**
 * Home shows a tappable, search-bar-styled row as the first item in its grid, so it scrolls away
 * naturally with the rest of the feed like any other list header (a real M3 [SearchBar] pinned
 * above the grid via an outer Box was tried first - found live: (1) it stayed fixed instead of
 * scrolling with content, which is what this row fixes, and (2) worse, M3's SearchBar crashes
 * with `IllegalArgumentException: Can't represent a width of ... and height of 2366967 in
 * Constraints` if its *expanded* state is ever measured inside a lazy list/grid item - it needs
 * genuinely bounded (screen-sized) constraints, which a scrolling container's item slot doesn't
 * give it. Tapping this row instead opens [SearchOverlayScreen] as a full-screen overlay from
 * `MainActivity`, the same proven-safe pattern already used for Queue/Lyrics/Stats - not a
 * separate route/tab; it used to be its own bottom-nav destination (ui/SearchScreen.kt), moved
 * here to match every mainstream music app's layout (search lives on/above the home feed).
 */
@Composable
fun HomeScreen(
    topTracks: List<HomeGridItem>,
    topArtists: List<TopArtistItem>,
    likedSongsCount: Int,
    onTrackClick: (HomeGridItem) -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
    onLikedSongsClick: () -> Unit,
    onOpenSearch: () -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    resolveArtistArt: suspend (String) -> Any?,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GRID_TILE_MIN_SIZE),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(GRID_SPACING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeSearchEntryRow(onClick = onOpenSearch)
        }

        if (topTracks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Play some songs and your most-played tracks will show up here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LikedSongsCard(count = likedSongsCount, onClick = onLikedSongsClick)
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

            if (topArtists.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TopArtistsRow(artists = topArtists, onArtistClick = onArtistClick, resolveArtistArt = resolveArtistArt)
                }
            }
        }
    }
}

/** Visually matches M3's SearchBar collapsed appearance (same default container color/shape/
 * height) without being one - see the [HomeScreen] doc for why a real SearchBar can't live here. */
@Composable
private fun HomeSearchEntryRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Search albums, artists, songs",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/**
 * Full-screen search overlay opened from [HomeSearchEntryRow] - rendered by `MainActivity`
 * alongside its other overlays (Queue/Lyrics/Stats), with a `BackHandler` there too (same
 * pattern as those). A plain `OutlinedTextField` rather than M3's `SearchBar`: this composable
 * already IS the full-screen "expanded" surface, so there's no separate collapsed state to
 * animate between - see the [HomeScreen] doc for the crash that ruled out `SearchBar` for this
 * app's layout entirely, not just the collapsed/inline spot.
 */
@Composable
fun SearchOverlayScreen(
    albums: List<DriveAlbum>,
    onAlbumClick: (DriveAlbum) -> Unit,
    onTrackClick: (album: DriveAlbum, index: Int) -> Unit,
    onBack: () -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, albums) { albums.searchLibrary(query) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search albums, artists, songs") },
                    singleLine = true,
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
                    } else null,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            when {
                query.isBlank() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Search your library by song, album, or artist name.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                results.albums.isEmpty() && results.tracks.isEmpty() -> Box(
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
                    if (results.albums.isNotEmpty()) {
                        item {
                            Text(
                                "Albums",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(results.albums, key = { "album-${it.id}" }) { album ->
                            AlbumResultRow(album = album, onClick = { onAlbumClick(album) }, resolveArt = resolveArt)
                        }
                    }
                    if (results.tracks.isNotEmpty()) {
                        item {
                            Text(
                                "Songs",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(results.tracks, key = { "track-${it.track.id}" }) { match ->
                            TrackResultRow(match = match, onClick = { onTrackClick(match.album, match.index) })
                        }
                    }
                }
            }
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
