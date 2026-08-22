package com.ettore.musicdrive.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

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

    companion object {
        const val DEFAULT_CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private val CACHE_LIMIT_BYTES_KEY = longPreferencesKey("cache_limit_bytes")
        private val LIBRARY_ROOT_FOLDER_ID_KEY = stringPreferencesKey("library_root_folder_id")
    }
}
