package com.ettore.musicdrive.data.drive

import android.media.MediaMetadataRetriever
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.source.MusicSource
import com.ettore.musicdrive.data.source.SourceType
import com.ettore.musicdrive.data.source.compoundId
import com.ettore.musicdrive.data.source.discFolderPattern
import com.ettore.musicdrive.data.source.discNumber
import com.ettore.musicdrive.data.source.leadingTrackNumber
import com.ettore.musicdrive.playback.driveMediaUri
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

// discFolderPattern/discNumber/leadingTrackNumber live in data.source.AlbumHeuristics now,
// shared with LocalMusicSource's equivalent walk - the multi-disc-merge and track-order-fallback
// heuristics only ever look at folder/file names, nothing Drive-specific.

/**
 * Drive's own `orderBy("name")` is a lexicographic STRING sort, so without this, "10 - Song.mp3"
 * sorts before "2 - Song.mp3" - a real, user-visible bug (album track order was scrambled for any
 * album past 9 tracks).
 */
private val trackOrderComparator = compareBy<DriveFile>({ leadingTrackNumber(it.name) }, { it.name })

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
    id = SourceType.DRIVE.compoundId(id),
    name = name,
    mimeType = mimeType,
    sizeBytes = getSize(),
)

private fun DriveFile.toDriveFolder() = DriveFolder(id = id, name = name)

class DriveRepository(private val tokenProvider: DriveTokenProvider) : MusicSource {

    override val type = SourceType.DRIVE
    override val requiresNetworkAuth = true

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    override fun mediaUri(rawTrackId: String): String = driveMediaUri(rawTrackId)

    /** Moved here from AlbumArtRepository, which used to build this exact Drive URL/header pair itself. */
    override suspend fun openRetriever(rawTrackId: String): MediaMetadataRetriever? {
        val token = tokenProvider.getAccessToken().getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(driveMediaUri(rawTrackId), mapOf("Authorization" to "Bearer $token"))
            retriever
        } catch (e: Exception) {
            retriever.release()
            null
        }
    }

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
        listAllPages { pageToken ->
            drive.files().list()
                .setQ("'$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setFields("nextPageToken, files(id, name)")
                .setOrderBy("name")
                .setPageSize(100)
                .setPageToken(pageToken)
                .execute()
                .let { it.files.orEmpty() to it.nextPageToken }
        }.map { it.toDriveFolder() }
    }

    /**
     * Drive's `pageSize` is a NOMINAL limit, not an exact one - the server can hand back fewer
     * results than requested even when more exist, via `nextPageToken`, and a caller that reads
     * only the first page silently drops whatever landed on later pages. This was a real bug: the
     * combined tracks query below (a big `or`-chained query across every album's source folders)
     * would occasionally come back short, so some albums loaded with a subset of their songs and
     * no indication anything was missing. Every multi-result Drive list call must page through
     * [nextPageToken] to exhaustion instead of trusting one `execute()` to be complete.
     */
    private suspend fun listAllPages(fetchPage: suspend (pageToken: String?) -> Pair<List<DriveFile>, String?>): List<DriveFile> {
        val results = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val (files, nextPageToken) = fetchPage(pageToken)
            results += files
            pageToken = nextPageToken
        } while (pageToken != null)
        return results
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
     * Every album (see [collectAlbumFolders]) found under [rootId],
     * each with its tracks. One traversal to find album folders, then one
     * combined query for every album's tracks, bucketed back to their album
     * via the track's parent folder id.
     */
    override suspend fun listLibraryAlbums(rootId: String): Result<List<DriveAlbum>> = withDrive { drive ->
        val albumFolders = collectAlbumFolders(drive, rootId, folderName = null, artistName = null)
        if (albumFolders.isEmpty()) return@withDrive emptyList()

        val parentsClause = albumFolders
            .flatMap { it.sourceFolderIds }
            .joinToString(separator = " or ") { "'$it' in parents" }
        val tracks = listAllPages { pageToken ->
            drive.files().list()
                .setQ("($parentsClause) and mimeType contains 'audio/' and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, size, parents)")
                .setOrderBy("name")
                .setPageSize(1000)
                .setPageToken(pageToken)
                .execute()
                .let { it.files.orEmpty() to it.nextPageToken }
        }

        // Real physical parent folder id -> tracks, sorted into real track-number order
        // within each folder (see trackOrderComparator - NOT Drive's own orderBy=name,
        // which is a lexicographic string sort and scrambles anything past track 9).
        val tracksByParentFolderId = tracks
            .groupBy { it.parents?.firstOrNull() }
            .mapValues { (_, folderTracks) -> folderTracks.sortedWith(trackOrderComparator) }
        albumFolders
            .map { entry ->
                DriveAlbum(
                    id = SourceType.DRIVE.compoundId(entry.folder.id),
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
