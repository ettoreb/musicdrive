package com.ettore.musicdrive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ettore.musicdrive.data.local.AlbumSortMode
import com.ettore.musicdrive.data.local.ThemeMode
import kotlin.math.roundToInt

private val cacheSizeOptions = listOf(
    "500 MB" to 500L * 1024 * 1024,
    "1 GB" to 1L * 1024 * 1024 * 1024,
    "2 GB" to 2L * 1024 * 1024 * 1024,
    "5 GB" to 5L * 1024 * 1024 * 1024,
    "10 GB" to 10L * 1024 * 1024 * 1024,
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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    defaultAlbumSortMode: AlbumSortMode,
    onDefaultAlbumSortModeChange: (AlbumSortMode) -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStorageDialog by remember { mutableStateOf(false) }

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

                SettingsSectionTitle("Streaming cache size")
                Text(
                    "Older cached tracks are evicted once this limit is reached. Downloaded songs and albums are separate and never evicted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsActionRow(
                    label = cacheSizeOptions.firstOrNull { it.second == cacheLimitBytes }?.first ?: "Custom",
                    onClick = { showStorageDialog = true },
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

@Composable
private fun StorageSizeDialog(current: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val currentIndex = cacheSizeOptions.indexOfFirst { it.second == current }.takeIf { it >= 0 } ?: 2
    var sliderIndex by remember { mutableStateOf(currentIndex.toFloat()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Streaming cache size", style = MaterialTheme.typography.titleMedium)
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
