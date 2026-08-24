package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ettore.musicdrive.data.drive.DriveAlbum

/**
 * A lightweight "Wrapped"-style recap of the existing most-played data
 * (same `PlayCountEntity`/`lastPlayedAt` Home already tracks) - a single
 * scrollable page rather than Metrolist's full swipeable story-card
 * carousel, which is a much bigger, separate feature (see CLAUDE.md).
 */
@Composable
fun StatsScreen(
    topTracks: List<HomeGridItem>,
    topArtists: List<TopArtistItem>,
    onBack: () -> Unit,
    resolveArt: suspend (DriveAlbum) -> Any?,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.statusBarsPadding()) {
            ScreenHeader(title = "Your Stats", onBack = onBack)

            if (topTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Play some songs and your stats will show up here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                val topTrack = topTracks.first()
                Text(
                    "Your #1 song",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                )
                HeroCard(
                    title = topTrack.track.name.withoutAudioExtension(),
                    subtitle = "${topTrack.album.name} · played ${topTrack.playCount} time" +
                        if (topTrack.playCount == 1) "" else "s",
                    album = topTrack.album,
                    resolveArt = resolveArt,
                )

                if (topArtists.isNotEmpty()) {
                    val topArtist = topArtists.first()
                    Text(
                        "Your top artist",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
                    )
                    HeroCard(
                        title = topArtist.artist.name,
                        subtitle = "${topArtist.playCount} play" + if (topArtist.playCount == 1) "" else "s",
                        album = topArtist.artist.albums.firstOrNull(),
                        resolveArt = resolveArt,
                        shape = CircleShape,
                    )
                }

                Text(
                    "Top songs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
                )
                topTracks.take(5).forEachIndexed { index, item ->
                    RankedRow(
                        rank = index + 1,
                        title = item.track.name.withoutAudioExtension(),
                        subtitle = item.album.name,
                        trailing = "${item.playCount}×",
                    )
                }

                if (topArtists.isNotEmpty()) {
                    Text(
                        "Top artists",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    topArtists.take(5).forEachIndexed { index, item ->
                        RankedRow(
                            rank = index + 1,
                            title = item.artist.name,
                            subtitle = null,
                            trailing = "${item.playCount}×",
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    album: DriveAlbum?,
    resolveArt: suspend (DriveAlbum) -> Any?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
) {
    var art by remember(album?.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(album?.id) { art = album?.let { resolveArt(it) } }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(placeholderColorFor(title)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RankedRow(rank: Int, title: String, subtitle: String?, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            trailing,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
