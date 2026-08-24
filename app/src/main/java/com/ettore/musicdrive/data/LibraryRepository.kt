package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.drive.DriveRepository
import com.ettore.musicdrive.data.local.room.AlbumEntity
import com.ettore.musicdrive.data.local.room.AlbumWithTracks
import com.ettore.musicdrive.data.local.room.LibraryDao
import com.ettore.musicdrive.data.local.room.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Shared with the Android Auto browse tree (MusicLibrarySessionCallback), which reads Room directly. */
fun AlbumWithTracks.toDriveAlbum() = DriveAlbum(
    id = album.id,
    name = album.name,
    artistHint = album.artistHint,
    tracks = tracks.map { DriveAudioFile(id = it.id, name = it.name, mimeType = it.mimeType, sizeBytes = it.sizeBytes) },
)

private fun DriveAlbum.toAlbumEntity(rootFolderId: String) =
    AlbumEntity(id = id, name = name, artistHint = artistHint, rootFolderId = rootFolderId)

private fun DriveAudioFile.toTrackEntity(albumId: String) =
    TrackEntity(id = id, albumId = albumId, name = name, mimeType = mimeType, sizeBytes = sizeBytes)

/**
 * Combines the Room-cached Drive index with live Drive fetches: the library
 * browses instantly from cache, then quietly refreshes in the background so
 * it stays in sync with the real Drive contents.
 */
class LibraryRepository(
    private val driveRepository: DriveRepository,
    private val libraryDao: LibraryDao,
) {

    /**
     * Emits the cached library immediately if there is one, then emits again
     * once the live Drive fetch completes. A background-refresh failure is
     * swallowed (not emitted) as long as cached data is already showing —
     * being offline shouldn't kick the user from "browsing a stale library"
     * to an error screen.
     */
    fun loadLibrary(rootFolderId: String): Flow<Result<List<DriveAlbum>>> = flow {
        val cached = libraryDao.getAlbumsWithTracks(rootFolderId).map { it.toDriveAlbum() }
        if (cached.isNotEmpty()) {
            emit(Result.success(cached))
        }

        driveRepository.listLibraryAlbums(rootFolderId).fold(
            onSuccess = { albums ->
                libraryDao.replaceLibrary(
                    rootFolderId,
                    albums.map { it.toAlbumEntity(rootFolderId) },
                    albums.flatMap { album -> album.tracks.map { it.toTrackEntity(album.id) } },
                )
                emit(Result.success(albums))
            },
            onFailure = { e ->
                if (cached.isEmpty()) {
                    emit(Result.failure(e))
                }
            },
        )
    }

    /** Only one library root is active at a time; call when the user picks a different folder. */
    suspend fun clearCache() {
        libraryDao.clearAll()
    }
}
