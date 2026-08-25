package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TrackOrderDao {
    @Query("SELECT * FROM TrackOrderEntity WHERE trackId IN (:trackIds)")
    suspend fun getForTracks(trackIds: List<String>): List<TrackOrderEntity>

    @Upsert
    suspend fun upsert(entity: TrackOrderEntity)
}
