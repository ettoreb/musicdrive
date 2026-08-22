package com.ettore.musicdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ettore.musicdrive.data.local.AlbumSortMode
import com.ettore.musicdrive.data.local.ThemeMode

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
    "Name (A–Z)" to AlbumSortMode.NAME,
    "Track count" to AlbumSortMode.TRACK_COUNT,
)

@Composable
fun SettingsScreen(
    cacheLimitBytes: Long,
    onCacheLimitChange: (Long) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    defaultAlbumSortMode: AlbumSortMode,
    onDefaultAlbumSortModeChange: (AlbumSortMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SettingsSectionTitle("Streaming cache size")
            Text(
                "Older cached tracks are evicted once this limit is reached. Downloaded songs and albums are separate and never evicted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            cacheSizeOptions.forEach { (label, bytes) ->
                SettingsRadioRow(label = label, selected = bytes == cacheLimitBytes, onClick = { onCacheLimitChange(bytes) })
            }

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
