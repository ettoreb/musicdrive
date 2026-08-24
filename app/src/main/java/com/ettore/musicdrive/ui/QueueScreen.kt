package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController

data class QueueItem(val mediaId: String, val title: String, val albumTitle: String)
data class QueueState(val items: List<QueueItem> = emptyList(), val currentIndex: Int = -1)

/** Mirrors a Media3 MediaController's playlist into Compose state, live. */
@Composable
fun rememberQueueState(controller: MediaController?): QueueState {
    var state by remember { mutableStateOf(QueueState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}

        fun refresh() {
            val items = (0 until controller.mediaItemCount).map { index ->
                val metadata = controller.getMediaItemAt(index).mediaMetadata
                QueueItem(
                    mediaId = controller.getMediaItemAt(index).mediaId,
                    title = metadata.title?.toString().orEmpty(),
                    albumTitle = metadata.albumTitle?.toString().orEmpty(),
                )
            }
            state = QueueState(items = items, currentIndex = controller.currentMediaItemIndex)
        }
        refresh()

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    return state
}

@Composable
fun QueueScreen(
    state: QueueState,
    isPlaying: Boolean,
    onTrackClick: (index: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Surface (not a plain .background() modifier) so it propagates the correct text
    // color to every un-colored Text below via LocalContentColor - see LyricsScreen's
    // matching comment for why this matters (real dark-mode bug, not a nitpick).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.statusBarsPadding()) {
            ScreenHeader(title = "Queue", onBack = onBack)

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { index, item -> "$index-${item.mediaId}" }) { index, item ->
                    val isCurrent = index == state.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { onTrackClick(index) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                            if (isCurrent && isPlaying) {
                                PlayingIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Column {
                            Text(
                                item.title.ifBlank { item.mediaId },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.albumTitle.isNotBlank()) {
                                Text(
                                    item.albumTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
