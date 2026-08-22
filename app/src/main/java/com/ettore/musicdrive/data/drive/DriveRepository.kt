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
    /**
     * The name of the root's direct child folder this album descends from,
     * when the library nests as root/Artist/.../Album (whatever the depth —
     * multi-disc releases add a Release/CD1 layer in between). Null for a
     * flatter root/Album layout.
     */
    val artistHint: String?,
    val tracks: List<DriveAudioFile>,
)

/** Matches "CD1", "CD 2", "Disc 1", "Disc 1 - Sounds Of The Universe", etc. - the real-world naming convention for multi-disc release subfolders. */
private val discFolderPattern = Regex("""(?i)^(cd|disc)\s*(\d+)""")

private fun discNumber(folderName: String): Int =
    discFolderPattern.find(folderName)?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE

private data class AlbumFolderWithArtist(
    val folder: DriveFolder,
    val artistName: String?,
    /**
     * Physical Drive folder ids whose direct children are this album's
     * tracks. Just [folder.id] normally, but every disc subfolder's id (in
     * disc order) when discs were merged under a shared release folder -
     * see [collectAlbumFolders].
     */
    val sourceFolderIds: List<String> = listOf(folder.id),
)

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

    private suspend fun resolveFolderName(drive: Drive, folderId: String): String = withContext(Dispatchers.IO) {
        drive.files().get(folderId).setFields("name").execute().name
    }

    /**
     * Depth-first search for "album" folders: the folder=album model doesn't
     * assume a fixed depth, since real libraries nest differently (e.g.
     * root/Album, root/Artist/Album, or root/Artist/Release/CD1 for
     * multi-disc releases). A folder is an album as soon as it directly
     * contains an audio file; otherwise its subfolders are searched.
     * [artistName] is null only while still resolving the root's direct
     * children; the first folder name seen right after the root becomes the
     * artist hint for everything found beneath it, no matter how many
     * Release/CDn layers come in between — using just the immediate parent
     * instead would mislabel a multi-disc release's CD1/CD2 folders (and
     * their own parent, the release name) as if they were artists.
     * [folderName] is only fetched with an extra call in the rare case where
     * the very first folder passed in already qualifies (its name usually
     * comes for free from the parent's listFoldersRaw call instead).
     *
     * Multi-disc releases (root/Artist/Release/CD1, .../CD2, or .../Disc 1,
     * .../Disc 2, ...) would otherwise surface each disc as its own "album"
     * since a disc folder directly contains audio files just like a normal
     * album folder does. Detected here instead: once every subfolder's own
     * recursion has come back, any subfolder whose result is a single leaf
     * album EQUAL TO ITSELF (it directly held audio, wasn't searched deeper)
     * and whose name matches the CD1/CD2/Disc-N convention is treated as a
     * disc of THIS folder's release, not a standalone album — this folder
     * becomes the merged album instead, with tracks drawn from every matched
     * disc subfolder (see sourceFolderIds / listLibraryAlbums).
     */
    private suspend fun collectAlbumFolders(
        drive: Drive,
        folderId: String,
        folderName: String?,
        artistName: String?,
    ): List<AlbumFolderWithArtist> = coroutineScope {
        if (folderHasAudioFiles(drive, folderId)) {
            val name = folderName ?: resolveFolderName(drive, folderId)
            listOf(AlbumFolderWithArtist(DriveFolder(folderId, name), artistName))
        } else {
            val childResults = listFoldersRaw(drive, folderId)
                .map { subfolder ->
                    async {
                        val childArtistName = artistName ?: subfolder.name
                        subfolder to collectAlbumFolders(drive, subfolder.id, subfolder.name, artistName = childArtistName)
                    }
                }
                .awaitAll()

            val discChildren = childResults.filter { (subfolder, results) ->
                results.size == 1 && results[0].folder.id == subfolder.id && discFolderPattern.containsMatchIn(subfolder.name)
            }

            if (discChildren.isEmpty()) {
                childResults.flatMap { it.second }
            } else {
                val releaseName = folderName ?: resolveFolderName(drive, folderId)
                val sourceFolderIds = discChildren
                    .sortedBy { (subfolder, _) -> discNumber(subfolder.name) }
                    .map { (subfolder, _) -> subfolder.id }
                val mergedAlbum = AlbumFolderWithArtist(
                    folder = DriveFolder(folderId, releaseName),
                    artistName = artistName,
                    sourceFolderIds = sourceFolderIds,
                )
                val discFolderIds = discChildren.map { (subfolder, _) -> subfolder.id }.toSet()
                val nonDiscResults = childResults.filterNot { (subfolder, _) -> subfolder.id in discFolderIds }.flatMap { it.second }
                listOf(mergedAlbum) + nonDiscResults
            }
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
        val albumFolders = collectAlbumFolders(drive, rootFolderId, folderName = null, artistName = null)
        if (albumFolders.isEmpty()) return@withDrive emptyList()

        val parentsClause = albumFolders
            .flatMap { it.sourceFolderIds }
            .joinToString(separator = " or ") { "'$it' in parents" }
        val tracks = drive.files().list()
            .setQ("($parentsClause) and mimeType contains 'audio/' and trashed = false")
            .setFields("files(id, name, mimeType, size, parents)")
            .setOrderBy("name")
            .setPageSize(1000)
            .execute()
            .files.orEmpty()

        // Real physical parent folder id -> tracks, name-sorted (Drive's orderBy=name
        // sorts the whole flat result set, and groupBy preserves each key's relative
        // order, so every per-folder sublist comes out name-sorted too).
        val tracksByParentFolderId = tracks.groupBy { it.parents?.firstOrNull() }
        albumFolders
            .map { entry ->
                DriveAlbum(
                    id = entry.folder.id,
                    name = entry.folder.name,
                    artistHint = entry.artistName,
                    // Concatenated in sourceFolderIds order (already disc-number-sorted
                    // for merged multi-disc albums), not re-sorted globally by name, so
                    // CD1's tracks all come before CD2's instead of interleaving.
                    tracks = entry.sourceFolderIds
                        .flatMap { tracksByParentFolderId[it].orEmpty() }
                        .map { it.toDriveAudioFile() },
                )
            }
            .filter { it.tracks.isNotEmpty() }
            .sortedBy { it.name }
    }
}
