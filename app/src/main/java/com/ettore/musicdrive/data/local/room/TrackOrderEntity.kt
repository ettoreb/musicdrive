package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * trackNumber == null means "looked it up before and found no embedded tag", cached so a
 * track with no tag isn't re-probed over the network every time its album is opened - same
 * "cache the miss too" pattern as [AlbumYearEntity]/[LyricsEntity].
 */
@Entity
data class TrackOrderEntity(
    @PrimaryKey val trackId: String,
    val trackNumber: Int?,
)
