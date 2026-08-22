package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LyricsDao {
    @Query("SELECT * FROM LyricsEntity WHERE trackId = :trackId")
    suspend fun get(trackId: String): LyricsEntity?

    @Upsert
    suspend fun upsert(entity: LyricsEntity)
}
