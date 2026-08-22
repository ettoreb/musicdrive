package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
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
}
