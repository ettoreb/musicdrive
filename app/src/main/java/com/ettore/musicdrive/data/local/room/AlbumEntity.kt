package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached album folder from one MusicSource. [id] is a compound "SOURCE:rawId" (see
 * SourceType.compoundId) so Drive and local rows can coexist in the same table without colliding.
 * Scoped by ([sourceType], [rootId]) rather than a single global root, since - unlike the old
 * single-source model - two sources can be active at once; refreshing one source's cache must
 * never touch the other's rows (see LibraryDao.replaceLibraryForSource/clearSource).
 */
@Entity(indices = [Index("sourceType"), Index("rootId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artistHint: String?,
    /** SourceType.name - an explicit column (not just parsed from [id]'s prefix) so "every row for this source" is a plain indexed query. */
    val sourceType: String,
    /** The active root for that source: a Drive folder id, or a local SAF tree Uri string. */
    val rootId: String,
)
