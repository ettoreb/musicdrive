package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PlayCountEntity(
    @PrimaryKey val trackId: String,
    val playCount: Int,
    val lastPlayedAt: Long,
)
