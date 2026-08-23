package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * year == null means "looked it up before and found nothing" (embedded tag
 * and iTunes fallback both came up empty), cached so we don't repeat that
 * lookup forever - same pattern as [LyricsEntity].
 */
@Entity
data class AlbumYearEntity(
    @PrimaryKey val albumId: String,
    val year: Int?,
)
