package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index("albumId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)
