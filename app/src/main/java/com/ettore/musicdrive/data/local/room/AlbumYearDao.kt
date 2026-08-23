package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AlbumYearDao {
    @Query("SELECT * FROM AlbumYearEntity WHERE albumId = :albumId")
    suspend fun get(albumId: String): AlbumYearEntity?

    @Upsert
    suspend fun upsert(entity: AlbumYearEntity)
}
