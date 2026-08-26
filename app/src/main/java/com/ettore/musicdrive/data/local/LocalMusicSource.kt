package com.ettore.musicdrive.data.local

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.source.MusicSource
import com.ettore.musicdrive.data.source.SourceType
import com.ettore.musicdrive.data.source.compoundId
import com.ettore.musicdrive.data.source.discFolderPattern
import com.ettore.musicdrive.data.source.discNumber
import com.ettore.musicdrive.data.source.leadingTrackNumber
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class LocalChildDoc(val id: String, val name: String, val mimeType: String, val sizeBytes: Long?)

private data class LocalAlbumFolder(
    val documentId: String,
    val name: String,
    val artistName: String?,
    /** Mirrors DriveRepository's AlbumFolderWithArtist.sourceFolderIds - every disc subfolder's document id, in disc order, when discs were merged under a shared release folder. */
    val sourceFolderIds: List<String> = listOf(documentId),
)

/**
 * Local on-device SAF folder as a MusicSource - the counterpart to DriveRepository. The
 * [listLibraryAlbums] `rootId` is the persisted SAF TREE URI STRING (see
 * SettingsRepository.localFolderTreeUri), not a document id; this class re-derives the tree Uri
 * from it and caches it in [currentTreeUri] for subsequent [mediaUri]/[openRetriever] calls -
 * this app only ever has ONE active local tree at a time (matches the local-folder on/off toggle
 * being a single choice, not a multi-root browser), so a single cached field is enough; it's kept
 * current on every [listLibraryAlbums] call, which always runs before any track from that listing
 * could be played or probed.
 */
class LocalMusicSource(private val context: Context) : MusicSource {

    override val type = SourceType.LOCAL
    override val requiresNetworkAuth = false

    /** Bounds concurrent SAF/ContentResolver calls during the folder walk - these are binder-IPC-backed the same way MediaMetadataRetriever is, and unbounded concurrent binder calls starved the thread pool once already in this app (see AlbumArtRepository's year-resolution Semaphore). */
    private val walkSemaphore = Semaphore(4)

    @Volatile private var currentTreeUri: Uri? = null

    private fun hasReadAccess(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    override fun mediaUri(rawTrackId: String): String {
        val treeUri = currentTreeUri ?: error("No local folder configured")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, rawTrackId).toString()
    }

    override suspend fun openRetriever(rawTrackId: String): MediaMetadataRetriever? = withContext(Dispatchers.IO) {
        val treeUri = currentTreeUri?.takeIf { hasReadAccess(it) } ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rawTrackId))
            retriever
        } catch (e: Exception) {
            retriever.release()
            null
        }
    }

    override suspend fun listLibraryAlbums(rootId: String): Result<List<DriveAlbum>> = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(rootId)
        if (!hasReadAccess(treeUri)) {
            return@withContext Result.failure(
                IllegalStateException("Local folder access was revoked - pick it again in Settings"),
            )
        }
        currentTreeUri = treeUri

        try {
            val trackCache = ConcurrentHashMap<String, List<LocalChildDoc>>()
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val albumFolders = collectAlbumFolders(treeUri, rootDocumentId, folderName = null, artistName = null, trackCache)

            val albums = albumFolders.map { entry ->
                val tracks = entry.sourceFolderIds
                    .flatMap { trackCache[it].orEmpty() }
                    .sortedWith(compareBy({ leadingTrackNumber(it.name) }, { it.name }))
                    .map {
                        DriveAudioFile(
                            id = SourceType.LOCAL.compoundId(it.id),
                            name = it.name,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                        )
                    }
                DriveAlbum(
                    id = SourceType.LOCAL.compoundId(entry.documentId),
                    name = entry.name,
                    artistHint = entry.artistName,
                    tracks = tracks,
                )
            }.filter { it.tracks.isNotEmpty() }.sortedBy { it.name }

            Result.success(albums)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun documentName(treeUri: Uri, documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) ?: documentId }
        return documentId
    }

    /**
     * One query per folder returns every child's id/name/mimeType/size at once - actually simpler
     * than Drive's split listFoldersRaw + folderHasAudioFiles (two separate queries), since SAF's
     * single query already carries MIME type per row, letting folders and audio files split out of
     * one pass.
     */
    private suspend fun listChildren(treeUri: Uri, folderId: String): List<LocalChildDoc> = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val results = mutableListOf<LocalChildDoc>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                results += LocalChildDoc(
                    id = cursor.getString(0),
                    name = cursor.getString(1) ?: cursor.getString(0),
                    mimeType = cursor.getString(2) ?: "",
                    sizeBytes = cursor.getLongOrNull(3),
                )
            }
        }
        results
    }

    /**
     * SAF equivalent of DriveRepository.collectAlbumFolders - same depth-first, name-only
     * heuristics (a folder that directly holds audio is a leaf album; a CD1/CD2/Disc-N-named leaf
     * subfolder merges into its parent's release instead of standing alone), just driven by SAF
     * queries instead of Drive API calls. Every leaf folder's own audio files are captured into
     * [trackCache] as they're discovered during the walk, avoiding a second combined query the way
     * Drive needs (SAF has no page-size/pagination concern to batch around).
     */
    private suspend fun collectAlbumFolders(
        treeUri: Uri,
        folderId: String,
        folderName: String?,
        artistName: String?,
        trackCache: MutableMap<String, List<LocalChildDoc>>,
    ): List<LocalAlbumFolder> = coroutineScope {
        val children = walkSemaphore.withPermit { listChildren(treeUri, folderId) }
        val subfolders = children.filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
        val audioFiles = children.filter { it.mimeType.startsWith("audio/") }

        if (audioFiles.isNotEmpty()) {
            trackCache[folderId] = audioFiles
            val name = folderName ?: documentName(treeUri, folderId)
            listOf(LocalAlbumFolder(folderId, name, artistName))
        } else {
            val childResults = subfolders
                .map { subfolder ->
                    async {
                        val childArtistName = artistName ?: subfolder.name
                        subfolder to collectAlbumFolders(treeUri, subfolder.id, subfolder.name, childArtistName, trackCache)
                    }
                }
                .awaitAll()

            val discChildren = childResults.filter { (subfolder, results) ->
                results.size == 1 && results[0].documentId == subfolder.id && discFolderPattern.containsMatchIn(subfolder.name)
            }

            if (discChildren.isEmpty()) {
                childResults.flatMap { it.second }
            } else {
                val releaseName = folderName ?: documentName(treeUri, folderId)
                val sourceFolderIds = discChildren
                    .sortedBy { (subfolder, _) -> discNumber(subfolder.name) }
                    .map { (subfolder, _) -> subfolder.id }
                val mergedAlbum = LocalAlbumFolder(folderId, releaseName, artistName, sourceFolderIds)
                val discFolderIds = discChildren.map { (subfolder, _) -> subfolder.id }.toSet()
                val nonDiscResults = childResults.filterNot { (subfolder, _) -> subfolder.id in discFolderIds }.flatMap { it.second }
                listOf(mergedAlbum) + nonDiscResults
            }
        }
    }
}

private fun Cursor.getLongOrNull(columnIndex: Int): Long? = if (isNull(columnIndex)) null else getLong(columnIndex)
