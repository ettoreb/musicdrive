package com.ettore.musicdrive.data.drive

import com.ettore.musicdrive.auth.DriveTokenProvider
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Google Drive's alias for "My Drive" itself, usable anywhere a folder id is expected. */
const val DRIVE_ROOT_FOLDER_ID = "root"

data class DriveAudioFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

data class DriveFolder(
    val id: String,
    val name: String,
)

data class DriveAlbum(
    val id: String,
    val name: String,
    /** The album folder's parent folder name, when the library nests as root/Artist/Album. Null for a flatter layout. */
    val artistHint: String?,
    val tracks: List<DriveAudioFile>,
)

private data class AlbumFolderWithParent(val folder: DriveFolder, val parentName: String?)

private fun DriveFile.toDriveAudioFile() = DriveAudioFile(
    id = id,
    name = name,
    mimeType = mimeType,
    sizeBytes = getSize(),
)

private fun DriveFile.toDriveFolder() = DriveFolder(id = id, name = name)

class DriveRepository(private val tokenProvider: DriveTokenProvider) {

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private suspend fun buildClient(token: String): Drive {
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
        }
        return Drive.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("MusicDrive")
            .build()
    }

    private suspend fun <T> withDrive(block: suspend (Drive) -> T): Result<T> = withContext(Dispatchers.IO) {
        val token = tokenProvider.getAccessToken().getOrElse {
            return@withContext Result.failure(it)
        }
        try {
            Result.success(block(buildClient(token)))
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 401) {
                tokenProvider.invalidate()
            }
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private suspend fun listFoldersRaw(drive: Drive, parentId: String): List<DriveFolder> = withContext(Dispatchers.IO) {
        drive.files().list()
            .setQ("'$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .setFields("files(id, name)")
            .setOrderBy("name")
            .setPageSize(100)
            .execute()
            .files.orEmpty().map { it.toDriveFolder() }
    }

    private suspend fun folderHasAudioFiles(drive: Drive, folderId: String): Boolean = withContext(Dispatchers.IO) {
        drive.files().list()
            .setQ("'$folderId' in parents and mimeType contains 'audio/' and trashed = false")
            .setFields("files(id)")
            .setPageSize(1)
            .execute()
            .files.orEmpty().isNotEmpty()
    }

    /**
     * Depth-first search for "album" folders: the folder=album model doesn't
     * assume a fixed depth, since real libraries nest differently (e.g.
     * root/Album vs root/Artist/Album). A folder is an album as soon as it
     * directly contains an audio file; otherwise its subfolders are searched.
     * [folderName] is only fetched with an extra call in the rare case where
     * the very first folder passed in already qualifies (its name usually
     * comes for free from the parent's listFoldersRaw call instead).
     */
    private suspend fun collectAlbumFolders(
        drive: Drive,
        folderId: String,
        folderName: String?,
        parentName: String?,
    ): List<AlbumFolderWithParent> = coroutineScope {
        if (folderHasAudioFiles(drive, folderId)) {
            val name = folderName ?: withContext(Dispatchers.IO) {
                drive.files().get(folderId).setFields("name").execute().name
            }
            listOf(AlbumFolderWithParent(DriveFolder(folderId, name), parentName))
        } else {
            listFoldersRaw(drive, folderId)
                .map { subfolder ->
                    async { collectAlbumFolders(drive, subfolder.id, subfolder.name, parentName = folderName) }
                }
                .awaitAll()
                .flatten()
        }
    }

    /** Subfolders of [parentId], for the in-app folder picker. Defaults to the Drive root ("My Drive"). */
    suspend fun listFolders(parentId: String = DRIVE_ROOT_FOLDER_ID): Result<List<DriveFolder>> =
        withDrive { drive -> listFoldersRaw(drive, parentId) }

    /**
     * Every album (see [collectAlbumFolders]) found under [rootFolderId],
     * each with its tracks. One traversal to find album folders, then one
     * combined query for every album's tracks, bucketed back to their album
     * via the track's parent folder id.
     */
    suspend fun listLibraryAlbums(rootFolderId: String): Result<List<DriveAlbum>> = withDrive { drive ->
        val albumFolders = collectAlbumFolders(drive, rootFolderId, folderName = null, parentName = null)
        if (albumFolders.isEmpty()) return@withDrive emptyList()

        val parentsClause = albumFolders.joinToString(separator = " or ") { "'${it.folder.id}' in parents" }
        val tracks = drive.files().list()
            .setQ("($parentsClause) and mimeType contains 'audio/' and trashed = false")
            .setFields("files(id, name, mimeType, size, parents)")
            .setOrderBy("name")
            .setPageSize(1000)
            .execute()
            .files.orEmpty()

        val tracksByAlbumFolderId = tracks.groupBy { it.parents?.firstOrNull() }
        albumFolders
            .map { entry ->
                DriveAlbum(
                    id = entry.folder.id,
                    name = entry.folder.name,
                    artistHint = entry.parentName,
                    tracks = tracksByAlbumFolderId[entry.folder.id].orEmpty().map { it.toDriveAudioFile() },
                )
            }
            .filter { it.tracks.isNotEmpty() }
            .sortedBy { it.name }
    }
}
