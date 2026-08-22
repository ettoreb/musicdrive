package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
)

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
        state = LyricsState(isLoading = false, text = result.lyrics, isInstrumental = result.isInstrumental)
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
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
