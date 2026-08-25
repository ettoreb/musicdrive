package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayCountDao {

    @Query(
        """
        INSERT INTO PlayCountEntity (trackId, playCount, lastPlayedAt) VALUES (:trackId, 1, :timestamp)
        ON CONFLICT(trackId) DO UPDATE SET playCount = playCount + 1, lastPlayedAt = :timestamp
        """,
    )
    suspend fun incrementPlay(trackId: String, timestamp: Long)

    @Query("SELECT * FROM PlayCountEntity ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeTopTracks(limit: Int): Flow<List<PlayCountEntity>>

    /** Every recorded play count, unranked - used to aggregate per-artist totals client-side (artist isn't tracked here, only per-track). */
    @Query("SELECT * FROM PlayCountEntity")
    fun observeAll(): Flow<List<PlayCountEntity>>

    /** One-shot count, so a startup restore check doesn't need to collect a Flow just to see if the table is empty. */
    @Query("SELECT COUNT(*) FROM PlayCountEntity")
    suspend fun count(): Int

    /** One-shot full read for exporting the local JSON backup (see PlayStatsRepository) - unranked, unlike [observeTopTracks]. */
    @Query("SELECT * FROM PlayCountEntity")
    suspend fun getAll(): List<PlayCountEntity>

    /** Bulk-reinsert from the on-disk backup (see PlayStatsRepository) after a destructive migration wiped this table. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(entities: List<PlayCountEntity>)
}
