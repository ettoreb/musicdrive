package com.ettore.musicdrive.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LibraryDao {

    @Transaction
    @Query("SELECT * FROM AlbumEntity WHERE rootFolderId = :rootFolderId ORDER BY name")
    suspend fun getAlbumsWithTracks(rootFolderId: String): List<AlbumWithTracks>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM TrackEntity WHERE albumId IN (SELECT id FROM AlbumEntity WHERE rootFolderId = :rootFolderId)")
    suspend fun deleteTracksForRoot(rootFolderId: String)

    @Query("DELETE FROM AlbumEntity WHERE rootFolderId = :rootFolderId")
    suspend fun deleteAlbumsForRoot(rootFolderId: String)

    @Query("DELETE FROM TrackEntity")
    suspend fun deleteAllTracks()

    @Query("DELETE FROM AlbumEntity")
    suspend fun deleteAllAlbums()

    /** Replaces the whole cached library for [rootFolderId] with a fresh listing. */
    @Transaction
    suspend fun replaceLibrary(rootFolderId: String, albums: List<AlbumEntity>, tracks: List<TrackEntity>) {
        // Tracks first: their delete query looks up album ids for this root, so the
        // albums must still be there to find them.
        deleteTracksForRoot(rootFolderId)
        deleteAlbumsForRoot(rootFolderId)
        insertAlbums(albums)
        insertTracks(tracks)
    }

    /** Only one library root is ever active at a time, so switching folders drops everything. */
    @Transaction
    suspend fun clearAll() {
        deleteAllTracks()
        deleteAllAlbums()
    }
}
