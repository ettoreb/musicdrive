package com.ettore.musicdrive.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [id]/[albumId] are compound "SOURCE:rawId" ids (see SourceType.compoundId). Room always stores
 * each source's OWN unmerged fetch results - [albumId] here is always from the same source as
 * [id] itself. Cross-source album merging (a local song taking precedence over its Drive
 * counterpart) is a pure in-memory step applied after reading, in
 * LibraryRepository.mergeSources - never written back to Room.
 */
@Entity(indices = [Index("albumId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)
