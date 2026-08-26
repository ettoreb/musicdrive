package com.ettore.musicdrive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.session.MediaController
import com.ettore.musicdrive.data.LyricsRepository
import kotlinx.coroutines.delay

private data class LyricsState(
    val isLoading: Boolean = true,
    val text: String? = null,
    val isInstrumental: Boolean = false,
    val syncedLines: List<LyricLine> = emptyList(),
)

/** One LRC-synced lyric line: when it starts, and its text. */
private data class LyricLine(val startMs: Long, val text: String)

private val lrcTagPattern = Regex("""^\[(\d{1,2}):(\d{1,2}(?:[.:]\d{1,3})?)](.*)$""")

/**
 * Parses LRCLIB's line-synced LRC text (`[mm:ss.xx]line text`) into timed lines - used to pick
 * which whole line is active and to drive auto-scroll. A prior version also synthesized
 * word-by-word highlighting by evenly distributing each line's words across its own timespan
 * (LRCLIB only has line-level timing, not real per-word timestamps) - reverted after it visibly
 * drifted out of sync with the actual vocals; line-level highlighting alone doesn't overclaim
 * precision the source data doesn't have.
 */
private fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence()
    .mapNotNull { raw ->
        val match = lrcTagPattern.matchEntire(raw.trim()) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].replace(':', '.').toDoubleOrNull() ?: return@mapNotNull null
        val text = match.groupValues[3].trim()
        if (text.isBlank()) return@mapNotNull null
        LyricLine(startMs = minutes * 60_000L + (seconds * 1000).toLong(), text = text)
    }
    .sortedBy { it.startMs }
    .toList()

/** Index of the last line whose start has passed, or -1 before the first line. */
private fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (lines[i].startMs <= positionMs) index = i else break
    }
    return index
}

/**
 * Embedded lyrics (an ID3 USLT frame) are only ever exposed via the raw
 * Player.Listener#onMetadata callback - Media3 has no dedicated parser for
 * them, they arrive as an opaque BinaryFrame (confirmed against the actual
 * androidx/media source, not assumed). [metadata] must come from that
 * callback, captured by the caller.
 */
@UnstableApi
private fun extractUnsynchronizedLyrics(metadata: Metadata): String? {
    for (i in 0 until metadata.length()) {
        val entry = metadata.get(i)
        if (entry is BinaryFrame && entry.id == "USLT") {
            return parseUslt(entry.data)
        }
    }
    return null
}

/**
 * ID3v2 USLT frame body: 1 byte text encoding, 3-byte language code, a
 * null-terminated content descriptor, then the lyrics text in the rest of
 * the frame. See id3.org/id3v2.4.0-frames.
 */
private fun parseUslt(data: ByteArray): String? {
    if (data.size < 5) return null
    val encodingByte = data[0].toInt() and 0xFF
    val charset = when (encodingByte) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }
    val terminatorSize = if (encodingByte == 1 || encodingByte == 2) 2 else 1

    var descriptorEnd = 4 // 1 (encoding) + 3 (language)
    while (descriptorEnd + terminatorSize <= data.size) {
        var isNull = true
        for (k in 0 until terminatorSize) {
            if (data[descriptorEnd + k] != 0.toByte()) {
                isNull = false
                break
            }
        }
        if (isNull) break
        descriptorEnd += terminatorSize
    }

    val lyricsStart = (descriptorEnd + terminatorSize).coerceAtMost(data.size)
    if (lyricsStart >= data.size) return null
    return runCatching { String(data, lyricsStart, data.size - lyricsStart, charset) }
        .getOrNull()
        ?.trim()
        ?.ifBlank { null }
}

/**
 * Mirrors embedded (live, from the player) and LRCLIB-fallback (repository)
 * lyrics for whatever [playerState] currently points at.
 */
@Composable
@UnstableApi
private fun rememberLyricsState(
    controller: MediaController?,
    playerState: PlayerUiState,
    lyricsRepository: LyricsRepository,
): LyricsState {
    var embeddedLyrics by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(LyricsState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                extractUnsynchronizedLyrics(metadata)?.let { embeddedLyrics = it }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(playerState.mediaId) {
        embeddedLyrics = null
        state = LyricsState(isLoading = true)
        if (playerState.mediaId.isBlank()) {
            state = LyricsState(isLoading = false)
            return@LaunchedEffect
        }
        // Embedded USLT frames, if present, are usually parsed within the first
        // moment of decoding - give onMetadata a brief window before deciding
        // there's nothing embedded and falling back to LRCLIB.
        delay(400)
        val result = lyricsRepository.getLyrics(
            trackId = playerState.mediaId,
            title = playerState.title,
            artist = playerState.artist,
            album = playerState.albumTitle,
            durationMs = playerState.durationMs,
            embeddedLyrics = embeddedLyrics,
        )
        val syncedLines = result.syncedLyrics?.let(::parseLrc).orEmpty()
        state = LyricsState(isLoading = false, text = result.lyrics, isInstrumental = result.isInstrumental, syncedLines = syncedLines)
    }

    return state
}

@Composable
@UnstableApi
fun LyricsScreen(
    controller: MediaController?,
    playerState: PlayerUiState,
    lyricsRepository: LyricsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLyricsState(controller, playerState, lyricsRepository)

    // Surface (not a plain .background() modifier) so it propagates the correct text
    // color to every un-colored Text below via LocalContentColor - a bare background()
    // modifier doesn't set it, which silently defaulted to black-on-black text in dark
    // mode (found live, real bug, not a color-tuning nitpick).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.statusBarsPadding()) {
            ScreenHeader(title = "Lyrics", onBack = onBack)

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.isInstrumental -> Text(
                        "Instrumental",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    state.text.isNullOrBlank() -> Text(
                        "No lyrics found for this track.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    state.syncedLines.isNotEmpty() -> SyncedLyricsView(
                        lines = state.syncedLines,
                        positionMs = playerState.positionMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text(
                        state.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(lines: List<LyricLine>, positionMs: Long, modifier: Modifier = Modifier) {
    val activeIndex = remember(lines, positionMs) { activeLineIndex(lines, positionMs) }
    val listState = rememberLazyListState()

    // Keeps a couple of already-sung lines visible above the active one, like a
    // typical karaoke display, rather than pinning the active line to the very top.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val isActive = index == activeIndex
            Text(
                line.text,
                style = if (isActive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
