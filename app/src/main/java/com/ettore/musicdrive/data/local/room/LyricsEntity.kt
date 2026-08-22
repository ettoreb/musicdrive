package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * lyrics == null means "looked it up before and found nothing" (embedded and
 * LRCLIB both came up empty), cached so we don't repeat that lookup forever.
 */
@Entity
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    val lyrics: String?,
    val source: String,
    val isInstrumental: Boolean,
)
