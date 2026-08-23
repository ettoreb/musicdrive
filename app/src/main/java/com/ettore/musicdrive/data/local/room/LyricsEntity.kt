package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * lyrics == null means "looked it up before and found nothing" (embedded and
 * LRCLIB both came up empty), cached so we don't repeat that lookup forever.
 * syncedLyrics is the raw LRC-format text (line-level `[mm:ss.xx]text` tags)
 * when LRCLIB has it - null for embedded (USLT is unsynced by spec) or when
 * LRCLIB only has plain lyrics for this track.
 */
@Entity
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    val lyrics: String?,
    val source: String,
    val isInstrumental: Boolean,
    val syncedLyrics: String? = null,
)
