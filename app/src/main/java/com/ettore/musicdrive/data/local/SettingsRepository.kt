package com.ettore.musicdrive.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryViewMode { ARTISTS, ALBUMS }

enum class AlbumSortMode { NAME, TRACK_COUNT, YEAR, ARTIST_NAME }

/** At most one cloud provider active at a time - see docs/multi-source-plan.md. Combinable with the independent local-folder toggle, never with another cloud provider. */
enum class CloudProvider { NONE, GOOGLE_DRIVE }

class SettingsRepository(private val context: Context) {

    /** The combined "Storage" limit: streaming cache + downloads together (see AdjustableLruEvictor.reservedBytes). Downloads are never auto-evicted regardless of this value - it only bounds how much room the streaming cache gets. */
    val cacheLimitBytes: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[CACHE_LIMIT_BYTES_KEY] ?: DEFAULT_CACHE_LIMIT_BYTES
    }

    suspend fun setCacheLimitBytes(bytes: Long) {
        context.settingsDataStore.edit { prefs -> prefs[CACHE_LIMIT_BYTES_KEY] = bytes }
    }

    /**
     * The Drive folder id the user picked as their library root, or null if not chosen yet.
     * Survives switching [cloudProvider] away from GOOGLE_DRIVE and back - only an actual
     * folder CHANGE (a different id picked) clears the Drive-source Room cache, a plain
     * provider toggle does not. Kept under its pre-multi-source DataStore key name
     * (library_root_folder_id) so an existing install's chosen root migrates for free.
     */
    val driveRootFolderId: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[DRIVE_ROOT_FOLDER_ID_KEY]
    }

    suspend fun setDriveRootFolderId(folderId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (folderId == null) prefs.remove(DRIVE_ROOT_FOLDER_ID_KEY) else prefs[DRIVE_ROOT_FOLDER_ID_KEY] = folderId
        }
    }

    /**
     * At most one cloud provider active at a time (see [CloudProvider]). No stored key yet on an
     * upgrade from a pre-multi-source install, so the default is derived rather than hardcoded to
     * NONE: an existing install with a Drive root already chosen comes back up as GOOGLE_DRIVE
     * (matching its actual prior behavior - Drive was its only, always-on source), not as
     * "nothing configured".
     */
    val cloudProvider: Flow<CloudProvider> = context.settingsDataStore.data.map { prefs ->
        prefs[CLOUD_PROVIDER_KEY]?.let { runCatching { CloudProvider.valueOf(it) }.getOrNull() }
            ?: if (prefs[DRIVE_ROOT_FOLDER_ID_KEY] != null) CloudProvider.GOOGLE_DRIVE else CloudProvider.NONE
    }

    suspend fun setCloudProvider(provider: CloudProvider) {
        context.settingsDataStore.edit { prefs -> prefs[CLOUD_PROVIDER_KEY] = provider.name }
    }

    /** Independent on/off toggle for the local on-device folder - combinable with a cloud source, unlike [cloudProvider] which is single-select. */
    val localFolderEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[LOCAL_FOLDER_ENABLED_KEY] ?: false
    }

    suspend fun setLocalFolderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[LOCAL_FOLDER_ENABLED_KEY] = enabled }
    }

    /**
     * The persisted SAF tree Uri (as a String) for the local folder, or null if never picked.
     * Survives [localFolderEnabled] being toggled off - re-enabling doesn't require re-picking.
     * The picker call site is responsible for calling
     * ContentResolver.takePersistableUriPermission before this is ever stored, or the grant won't
     * survive a reboot.
     */
    val localFolderTreeUri: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[LOCAL_FOLDER_TREE_URI_KEY]
    }

    suspend fun setLocalFolderTreeUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri == null) prefs.remove(LOCAL_FOLDER_TREE_URI_KEY) else prefs[LOCAL_FOLDER_TREE_URI_KEY] = uri
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
        // Kept as "library_root_folder_id" (its pre-multi-source name) so an existing install's
        // chosen Drive root migrates for free - see driveRootFolderId's doc comment.
        private val DRIVE_ROOT_FOLDER_ID_KEY = stringPreferencesKey("library_root_folder_id")
        private val CLOUD_PROVIDER_KEY = stringPreferencesKey("cloud_provider")
        private val LOCAL_FOLDER_ENABLED_KEY = booleanPreferencesKey("local_folder_enabled")
        private val LOCAL_FOLDER_TREE_URI_KEY = stringPreferencesKey("local_folder_tree_uri")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val LIBRARY_VIEW_MODE_KEY = stringPreferencesKey("library_view_mode")
        private val ALBUM_SORT_MODE_KEY = stringPreferencesKey("album_sort_mode")
    }
}
