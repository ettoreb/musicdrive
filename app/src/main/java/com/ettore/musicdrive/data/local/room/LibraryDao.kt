package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LibraryDao {

    /** Every cached album, from whichever source(s) have ever been fetched - source-blind on purpose; merging by which source(s) are currently enabled happens one layer up, in LibraryRepository. */
    @Transaction
    @Query("SELECT * FROM AlbumEntity ORDER BY name")
    suspend fun getAlbumsWithTracks(): List<AlbumWithTracks>

    @Transaction
    @Query("SELECT * FROM AlbumEntity WHERE sourceType = :sourceType AND rootId = :rootId ORDER BY name")
    suspend fun getAlbumsWithTracks(sourceType: String, rootId: String): List<AlbumWithTracks>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM TrackEntity WHERE albumId IN (SELECT id FROM AlbumEntity WHERE sourceType = :sourceType AND rootId = :rootId)")
    suspend fun deleteTracksForRoot(sourceType: String, rootId: String)

    @Query("DELETE FROM AlbumEntity WHERE sourceType = :sourceType AND rootId = :rootId")
    suspend fun deleteAlbumsForRoot(sourceType: String, rootId: String)

    @Query("DELETE FROM TrackEntity WHERE albumId IN (SELECT id FROM AlbumEntity WHERE sourceType = :sourceType)")
    suspend fun deleteTracksForSource(sourceType: String)

    @Query("DELETE FROM AlbumEntity WHERE sourceType = :sourceType")
    suspend fun deleteAlbumsForSource(sourceType: String)

    /** Replaces one source's cached library at [rootId] with a fresh listing - never touches the other source's rows. */
    @Transaction
    suspend fun replaceLibraryForSource(sourceType: String, rootId: String, albums: List<AlbumEntity>, tracks: List<TrackEntity>) {
        // Tracks first: their delete query looks up album ids for this (source, root), so the
        // albums must still be there to find them.
        deleteTracksForRoot(sourceType, rootId)
        deleteAlbumsForRoot(sourceType, rootId)
        insertAlbums(albums)
        insertTracks(tracks)
    }

    /** Drops every cached row for one source - used when that source's root/tree itself CHANGES (a different Drive folder or local SAF tree picked), not when a source is merely toggled off/on. */
    @Transaction
    suspend fun clearSource(sourceType: String) {
        deleteTracksForSource(sourceType)
        deleteAlbumsForSource(sourceType)
    }
}
