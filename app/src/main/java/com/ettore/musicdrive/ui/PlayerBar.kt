package com.ettore.musicdrive.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

data class PlayerUiState(
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val albumTitle: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The current track's embedded artwork, as Media3 already extracted it while decoding the stream. */
    val artworkData: ByteArray? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
) {
    val hasTrack: Boolean get() = title.isNotEmpty() || artist.isNotEmpty() || isPlaying
}

/** Decodes [PlayerUiState.artworkData] once per change, not on every recomposition. */
@Composable
private fun rememberArtBitmap(artworkData: ByteArray?): ImageBitmap? = remember(artworkData) {
    artworkData?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
}

/**
 * A per-track accent color pulled from the artwork itself (Palette API),
 * used to tint the full player's background - the same "glow from the
 * cover" ambient effect YouTube Music/Spotify use. Vibrant swatch first
 * since it reads best as a background tint; falls back to muted/dominant
 * when the art has no strongly saturated color (e.g. mostly-white covers).
 * Decoded once per artwork change, same as [rememberArtBitmap] - not
 * shared with it to keep that function's existing callers untouched.
 */
@Composable
private fun rememberDominantColor(artworkData: ByteArray?): Color? = remember(artworkData) {
    artworkData
        ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        ?.let { bitmap ->
            val palette = Palette.from(bitmap).generate()
            val swatch = palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
            swatch?.rgb?.let { Color(it) }
        }
}

/** Mirrors a Media3 MediaController's playback state into Compose state, live. */
@Composable
fun rememberPlayerUiState(controller: MediaController?): PlayerUiState {
    var state by remember { mutableStateOf(PlayerUiState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}

        fun refresh() {
            val metadata = controller.mediaMetadata
            state = PlayerUiState(
                mediaId = controller.currentMediaItem?.mediaId.orEmpty(),
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                albumTitle = metadata.albumTitle?.toString().orEmpty(),
                isPlaying = controller.isPlaying,
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.coerceAtLeast(0),
                artworkData = metadata.artworkData,
                repeatMode = controller.repeatMode,
                shuffleModeEnabled = controller.shuffleModeEnabled,
            )
        }
        refresh()

        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()
            override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
            override fun onPlaybackStateChanged(playbackState: Int) = refresh()
            override fun onRepeatModeChanged(repeatMode: Int) = refresh()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller, state.isPlaying) {
        if (controller == null || !state.isPlaying) return@LaunchedEffect
        while (true) {
            delay(500)
            state = state.copy(
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.coerceAtLeast(0),
            )
        }
    }

    return state
}

@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onPlayPauseClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The current album's disk-cached cover (same art already resolved for the library grid),
    // shown while state.artworkData is still catching up - see FullPlayerScreen's kdoc.
    fallbackArt: Any? = null,
) {
    val artBitmap = rememberArtBitmap(state.artworkData)
    // Accumulated vertical drag; a swipe up past the threshold expands the full
    // player too, same as tapping - a lighter-weight stand-in for Auxio's
    // follow-the-finger BottomSheetBehavior drag (not portable from its
    // View-based implementation; this reuses the existing tap target instead
    // of a real anchored drag-and-settle animation).
    var dragUpTotal by remember { mutableStateOf(0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(onClick) {
                detectVerticalDragGestures(
                    onDragStart = { dragUpTotal = 0f },
                    onDragEnd = { if (dragUpTotal <= -60f) onClick() },
                    onDragCancel = { dragUpTotal = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    dragUpTotal += dragAmount
                }
            },
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (artBitmap != null) {
                    Image(
                        bitmap = artBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (fallbackArt != null) {
                    AsyncImage(
                        model = fallbackArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        state.title.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.title.ifBlank { "Loading…" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.artist.isNotBlank()) {
                    Text(
                        state.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onPlayPauseClick) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

@Composable
fun FullPlayerScreen(
    state: PlayerUiState,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    // The current album's disk-cached cover (AlbumArtRepository.resolveArt, the same art
    // already resolved for the library grid). state.artworkData comes from Media3 extracting
    // ID3 art out of the live stream, which lags a beat on every skip - the album's already-
    // resolved cover fills that gap instead of the bare initial-letter placeholder flashing.
    fallbackArt: Any? = null,
) {
    val artBitmap = rememberArtBitmap(state.artworkData)
    val dominantColor = rememberDominantColor(state.artworkData)
    // Accumulated horizontal drag since the gesture started; a swipe past the
    // threshold on release skips forward/back, like a mini cover carousel.
    var dragTotal by remember { mutableStateOf(0f) }

    // Surface (not a plain .background() modifier) so it propagates the correct text
    // color to every un-colored Text below via LocalContentColor - this screen is an
    // overlay rendered outside the Scaffold that normally provides that, and a bare
    // background() modifier doesn't set it, which silently defaulted to black-on-black
    // text in dark mode (found live, real bug, not a color-tuning nitpick).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Box(modifier = Modifier.fillMaxSize()) {
    // Per-track ambient glow from the album art's dominant color, fading into the
    // theme surface - purely decorative, drawn behind everything else, so it never
    // affects the LocalContentColor propagation Surface above sets up for the text.
    if (dominantColor != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(dominantColor.copy(alpha = 0.35f), Color.Transparent))),
        )
    }
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
        }

        // Art through controls is centered as ONE block (rather than split between two
        // independent weight(1f) spacers) so a track with a 2-line title doesn't visibly
        // squeeze/shift the whole layout relative to a 1-line one on every skip - found live,
        // real jank, not a nitpick.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(onNextClick, onPreviousClick) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            val threshold = 120f
                            if (dragTotal <= -threshold) onNextClick()
                            else if (dragTotal >= threshold) onPreviousClick()
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (artBitmap != null) {
                Image(
                    bitmap = artBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackArt != null) {
                AsyncImage(
                    model = fallbackArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    state.title.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Start) {
            IconButton(onClick = onOpenLyrics) {
                Icon(Icons.Filled.Lyrics, contentDescription = "Lyrics")
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            state.title.ifBlank { "Loading…" },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee(),
        )
        if (state.artist.isNotBlank()) {
            Text(
                state.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        val progressFraction = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        SquigglySlider(
            progressFraction = progressFraction,
            onSeek = { fraction -> onSeek((fraction * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    if (state.shuffleModeEnabled) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                    contentDescription = if (state.shuffleModeEnabled) "Shuffle: on" else "Shuffle: off",
                    tint = if (state.shuffleModeEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onPreviousClick) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
            }
            FilledIconButton(onClick = onPlayPauseClick, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onNextClick) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                        Player.REPEAT_MODE_ALL -> Icons.Filled.RepeatOn
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat: one track"
                        Player.REPEAT_MODE_ALL -> "Repeat: all"
                        else -> "Repeat: off"
                    },
                    tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        }
    }
    }
    }
}

/**
 * A hand-drawn straight seek bar on a [Canvas] (rather than the stock
 * [androidx.compose.material3.Slider], to match this screen's custom look
 * and keep the drag/tap-to-seek gesture handling below self-contained).
 */
@Composable
private fun SquigglySlider(
    progressFraction: Float,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While the user is actively dragging, show their finger's position instead of
    // the real (laggier) playback position; commit the seek only on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownFraction = dragFraction ?: progressFraction

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Canvas(
        modifier = modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction?.let(onSeek); dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val centerY = size.height / 2f
        val progressX = size.width * shownFraction
        val strokeWidth = 4.dp.toPx()

        drawLine(
            color = primaryColor,
            start = Offset(0f, centerY),
            end = Offset(progressX, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = trackColor,
            start = Offset(progressX, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        drawCircle(color = primaryColor, radius = 6.dp.toPx(), center = Offset(progressX, centerY))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
