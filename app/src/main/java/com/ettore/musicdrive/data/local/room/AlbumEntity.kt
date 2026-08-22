package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A cached album (Drive folder). Scoped by [rootFolderId] since only one library root is active at a time. */
@Entity(indices = [Index("rootFolderId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artistHint: String?,
    val rootFolderId: String,
)
