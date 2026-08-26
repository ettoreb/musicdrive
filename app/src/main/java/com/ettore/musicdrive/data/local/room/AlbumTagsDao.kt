package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AlbumTagsDao {
    @Query("SELECT * FROM AlbumTagsEntity WHERE albumId = :albumId")
    suspend fun get(albumId: String): AlbumTagsEntity?

    @Upsert
    suspend fun upsert(entity: AlbumTagsEntity)
}
