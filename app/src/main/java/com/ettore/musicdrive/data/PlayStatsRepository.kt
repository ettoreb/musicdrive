package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.local.room.PlayCountDao
import com.ettore.musicdrive.data.local.room.PlayCountEntity
import com.ettore.musicdrive.data.source.SourceType
import com.ettore.musicdrive.data.source.compoundId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Play counts are the one piece of Room-cached data with no other source of truth: the library
 * index, lyrics, art, release year, and track order are all just caches of something re-fetchable
 * from Drive/LRCLIB/embedded tags/iTunes, so wiping them costs a slower next launch, not real
 * data. This project wipes the WHOLE Room database on every schema change instead of writing
 * migrations (pre-release, schema still moving - see MusicDriveApplication), which used to take
 * the user's actual listening history down with it. [backupFile] is a plain JSON file OUTSIDE the
 * Room database directory, so a destructive migration never touches it: written after every
 * recorded play, and restored once at startup if Room's table comes up empty (a fresh install
 * with no backup yet is the same no-op path, so this is safe to call unconditionally).
 */
class PlayStatsRepository(private val playCountDao: PlayCountDao, private val backupFile: File) {

    fun observeTopTracks(limit: Int): Flow<List<PlayCountEntity>> = playCountDao.observeTopTracks(limit)

    fun observeAll(): Flow<List<PlayCountEntity>> = playCountDao.observeAll()

    suspend fun recordPlay(trackId: String) {
        playCountDao.incrementPlay(trackId, System.currentTimeMillis())
        writeBackup()
    }

    /** Called once at app startup, before anything else needs play counts. */
    suspend fun restoreFromBackupIfEmpty() = withContext(Dispatchers.IO) {
        if (playCountDao.count() > 0) return@withContext
        val entities = readBackup() ?: return@withContext
        if (entities.isNotEmpty()) playCountDao.restoreAll(entities)
    }

    private suspend fun writeBackup() = withContext(Dispatchers.IO) {
        val json = JSONArray()
        playCountDao.getAll().forEach { entity ->
            json.put(
                JSONObject()
                    .put("trackId", entity.trackId)
                    .put("playCount", entity.playCount)
                    .put("lastPlayedAt", entity.lastPlayedAt),
            )
        }
        runCatching { backupFile.writeText(json.toString()) }
    }

    private fun readBackup(): List<PlayCountEntity>? = runCatching {
        if (!backupFile.exists()) return null
        val json = JSONArray(backupFile.readText())
        (0 until json.length()).map { i ->
            val obj = json.getJSONObject(i)
            PlayCountEntity(
                trackId = obj.getString("trackId").toCompoundTrackId(),
                playCount = obj.getInt("playCount"),
                lastPlayedAt = obj.getLong("lastPlayedAt"),
            )
        }
    }.getOrNull()

    /**
     * A backup written before the multi-source id format landed stores bare Drive file ids
     * (e.g. "1a2b3c"), not "DRIVE:1a2b3c" - every pre-existing backup only ever came from Drive
     * tracks (local sources didn't exist yet), so a legacy id is always safe to prefix as DRIVE.
     * Without this, an upgrading install's real listening history would silently fail to match
     * against the new mediaId format and look like it reset to zero - defeating the entire reason
     * this backup file exists (see the class doc comment).
     */
    private fun String.toCompoundTrackId(): String =
        if (SourceType.entries.any { startsWith("${it.name}:") }) this else SourceType.DRIVE.compoundId(this)
}
