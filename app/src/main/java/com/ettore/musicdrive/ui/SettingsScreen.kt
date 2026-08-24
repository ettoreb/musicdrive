package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.exoplayer.offline.Download
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.local.AlbumSortMode
import com.ettore.musicdrive.data.local.ThemeMode
import kotlin.math.roundToInt

private val cacheSizeOptions = listOf(
    "1 GB" to 1L * 1024 * 1024 * 1024,
    "2 GB" to 2L * 1024 * 1024 * 1024,
    "5 GB" to 5L * 1024 * 1024 * 1024,
    "10 GB" to 10L * 1024 * 1024 * 1024,
    "15 GB" to 15L * 1024 * 1024 * 1024,
    "20 GB" to 20L * 1024 * 1024 * 1024,
)

private val themeModeOptions = listOf(
    "Follow system" to ThemeMode.SYSTEM,
    "Light" to ThemeMode.LIGHT,
    "Dark" to ThemeMode.DARK,
)

private val albumSortOptions = listOf(
    "Release year (newest first)" to AlbumSortMode.YEAR,
    "Name (A–Z)" to AlbumSortMode.NAME,
    "Track count" to AlbumSortMode.TRACK_COUNT,
)

@Composable
fun SettingsScreen(
    libraryFolderLabel: String,
    onChangeFolder: () -> Unit,
    cacheLimitBytes: Long,
    onCacheLimitChange: (Long) -> Unit,
    streamingCacheUsageBytes: Long,
    artDiskUsageBytes: Long,
    downloads: Map<String, Download>,
    albums: List<DriveAlbum>,
    onRemoveDownloadedAlbum: (DriveAlbum) -> Unit,
    onRemoveAllDownloads: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    defaultAlbumSortMode: AlbumSortMode,
    onDefaultAlbumSortModeChange: (AlbumSortMode) -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStorageDialog by remember { mutableStateOf(false) }
    val downloadsUsageBytes = remember(downloads) { downloads.values.sumOf { it.bytesDownloaded } }
    val downloadGroups = remember(downloads, albums) { groupDownloadsByAlbum(downloads, albums) }

    // Surface (not a plain .background() modifier) so it propagates the correct text
    // color to every un-colored Text below via LocalContentColor - a bare background()
    // modifier doesn't set it, which silently defaulted to black-on-black text in dark
    // mode (found live, real bug, not a color-tuning nitpick).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            ScreenHeader(title = "Settings", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSectionTitle("Library")
                SettingsActionRow(label = libraryFolderLabel, onClick = onChangeFolder)

                SettingsSectionTitle("Storage")
                Text(
                    "Downloads count toward this limit but are never deleted automatically. " +
                        "The streaming cache uses whatever room is left, evicting your least-played songs first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsActionRow(
                    label = cacheSizeOptions.firstOrNull { it.second == cacheLimitBytes }?.first ?: formatBytes(cacheLimitBytes),
                    onClick = { showStorageDialog = true },
                )
                Spacer(Modifier.height(8.dp))
                StorageUsageBar(downloadsBytes = downloadsUsageBytes, streamingBytes = streamingCacheUsageBytes, limitBytes = cacheLimitBytes)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatBytes(downloadsUsageBytes)} downloads · ${formatBytes(streamingCacheUsageBytes)} streaming cache · " +
                        "${formatBytes(cacheLimitBytes)} limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadsUsageBytes > cacheLimitBytes) {
                    Text(
                        "Downloads alone are over your limit - none were deleted, but the streaming cache has no room left. " +
                            "Raise the limit or remove some downloads below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "${formatBytes(artDiskUsageBytes)} album art · always kept for instant browsing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                SettingsSectionTitle("Appearance")
                themeModeOptions.forEach { (label, mode) ->
                    SettingsRadioRow(label = label, selected = mode == themeMode, onClick = { onThemeModeChange(mode) })
                }

                SettingsSectionTitle("Default album sort")
                albumSortOptions.forEach { (label, mode) ->
                    SettingsRadioRow(
                        label = label,
                        selected = mode == defaultAlbumSortMode,
                        onClick = { onDefaultAlbumSortModeChange(mode) },
                    )
                }

                SettingsSectionTitle("Downloads")
                if (downloadGroups.isEmpty()) {
                    Text(
                        "No downloads yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    downloadGroups.forEach { group ->
                        DownloadRow(
                            title = group.album?.name ?: "Other downloads (${group.trackIds.size})",
                            sizeBytes = group.sizeBytes,
                            onRemove = group.album?.let { album -> { onRemoveDownloadedAlbum(album) } },
                        )
                    }
                    TextButton(onClick = onRemoveAllDownloads, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Remove all downloads")
                    }
                }

                SettingsSectionTitle("Stats")
                SettingsActionRow(label = "Your Stats (most-played songs & artists)", onClick = onOpenStats)

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showStorageDialog) {
        StorageSizeDialog(
            current = cacheLimitBytes,
            onConfirm = { bytes -> onCacheLimitChange(bytes); showStorageDialog = false },
            onDismiss = { showStorageDialog = false },
        )
    }
}

private data class DownloadGroup(val album: DriveAlbum?, val trackIds: List<String>, val sizeBytes: Long)

/** Album membership rides in DownloadRequest.data (the album's Drive folder id, UTF-8 bytes) - see DownloadTracker.downloadTrack. Empty/unresolvable ids (e.g. a stale download from a since-changed library root) bucket together under a null album. */
private fun groupDownloadsByAlbum(downloads: Map<String, Download>, albums: List<DriveAlbum>): List<DownloadGroup> =
    downloads.entries
        .groupBy { (_, download) -> String(download.request.data, Charsets.UTF_8) }
        .map { (albumId, entries) ->
            DownloadGroup(
                album = albums.find { it.id == albumId },
                trackIds = entries.map { it.key },
                sizeBytes = entries.sumOf { it.value.bytesDownloaded },
            )
        }
        .sortedByDescending { it.sizeBytes }

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024)
    return "%.0f MB".format(mb)
}

@Composable
private fun StorageUsageBar(downloadsBytes: Long, streamingBytes: Long, limitBytes: Long) {
    val limit = limitBytes.toFloat().coerceAtLeast(1f)
    val downloadsFraction = (downloadsBytes / limit).coerceIn(0f, 1f)
    val streamingFraction = (streamingBytes / limit).coerceIn(0f, 1f - downloadsFraction)
    val freeFraction = (1f - downloadsFraction - streamingFraction).coerceAtLeast(0f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (downloadsFraction > 0f) {
            Box(Modifier.weight(downloadsFraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        if (streamingFraction > 0f) {
            Box(Modifier.weight(streamingFraction).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
        }
        if (freeFraction > 0f) {
            Box(Modifier.weight(freeFraction).fillMaxHeight())
        }
    }
}

@Composable
private fun DownloadRow(title: String, sizeBytes: Long, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove $title")
            }
        }
    }
}

@Composable
private fun StorageSizeDialog(current: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val currentIndex = cacheSizeOptions.indexOfFirst { it.second == current }.takeIf { it >= 0 } ?: 1
    var sliderIndex by remember { mutableStateOf(currentIndex.toFloat()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Storage limit", style = MaterialTheme.typography.titleMedium)
                Text(
                    cacheSizeOptions[sliderIndex.roundToInt()].first,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Slider(
                    value = sliderIndex,
                    onValueChange = { sliderIndex = it },
                    valueRange = 0f..(cacheSizeOptions.size - 1).toFloat(),
                    steps = cacheSizeOptions.size - 2,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(cacheSizeOptions[sliderIndex.roundToInt()].second) }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SettingsActionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
