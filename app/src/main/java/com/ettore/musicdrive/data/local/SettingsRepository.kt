package com.ettore.musicdrive.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryViewMode { ARTISTS, ALBUMS }

enum class AlbumSortMode { NAME, TRACK_COUNT, YEAR }

class SettingsRepository(private val context: Context) {

    val cacheLimitBytes: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[CACHE_LIMIT_BYTES_KEY] ?: DEFAULT_CACHE_LIMIT_BYTES
    }

    suspend fun setCacheLimitBytes(bytes: Long) {
        context.settingsDataStore.edit { prefs -> prefs[CACHE_LIMIT_BYTES_KEY] = bytes }
    }

    /** The Drive folder id the user picked as their library root, or null if not chosen yet. */
    val libraryRootFolderId: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[LIBRARY_ROOT_FOLDER_ID_KEY]
    }

    suspend fun setLibraryRootFolderId(folderId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (folderId == null) prefs.remove(LIBRARY_ROOT_FOLDER_ID_KEY) else prefs[LIBRARY_ROOT_FOLDER_ID_KEY] = folderId
        }
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    /** Which sub-view of the Library tab (Artists vs. flat Albums grid) was open last, so it's remembered across launches. */
    val libraryViewMode: Flow<LibraryViewMode> = context.settingsDataStore.data.map { prefs ->
        prefs[LIBRARY_VIEW_MODE_KEY]?.let { runCatching { LibraryViewMode.valueOf(it) }.getOrNull() } ?: LibraryViewMode.ARTISTS
    }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        context.settingsDataStore.edit { prefs -> prefs[LIBRARY_VIEW_MODE_KEY] = mode.name }
    }

    val albumSortMode: Flow<AlbumSortMode> = context.settingsDataStore.data.map { prefs ->
        prefs[ALBUM_SORT_MODE_KEY]?.let { runCatching { AlbumSortMode.valueOf(it) }.getOrNull() } ?: AlbumSortMode.YEAR
    }

    suspend fun setAlbumSortMode(mode: AlbumSortMode) {
        context.settingsDataStore.edit { prefs -> prefs[ALBUM_SORT_MODE_KEY] = mode.name }
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private val CACHE_LIMIT_BYTES_KEY = longPreferencesKey("cache_limit_bytes")
        private val LIBRARY_ROOT_FOLDER_ID_KEY = stringPreferencesKey("library_root_folder_id")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val LIBRARY_VIEW_MODE_KEY = stringPreferencesKey("library_view_mode")
        private val ALBUM_SORT_MODE_KEY = stringPreferencesKey("album_sort_mode")
    }
}
