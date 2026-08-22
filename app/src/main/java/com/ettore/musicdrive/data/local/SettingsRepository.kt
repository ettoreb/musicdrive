package com.ettore.musicdrive.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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

    companion object {
        const val DEFAULT_CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private val CACHE_LIMIT_BYTES_KEY = longPreferencesKey("cache_limit_bytes")
    }
}
