package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Embedded TALB/TPE2(albumartist)/TPE1(artist) tag values for an album's first track, when
 * present - null fields mean "looked it up before and found no tag", cached so we don't
 * re-probe forever, same pattern as [AlbumYearEntity].
 */
@Entity
data class AlbumTagsEntity(
    @PrimaryKey val albumId: String,
    val tagAlbumName: String?,
    val tagArtistName: String?,
)
