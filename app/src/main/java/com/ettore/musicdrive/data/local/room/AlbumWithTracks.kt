package com.ettore.musicdrive.data.local.room

import androidx.room.Embedded
import androidx.room.Relation

data class AlbumWithTracks(
    @Embedded val album: AlbumEntity,
    @Relation(parentColumn = "id", entityColumn = "albumId")
    val tracks: List<TrackEntity>,
)
