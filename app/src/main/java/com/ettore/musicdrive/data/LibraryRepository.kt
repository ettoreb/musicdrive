package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.local.CloudProvider
import com.ettore.musicdrive.data.local.SettingsRepository
import com.ettore.musicdrive.data.local.room.AlbumEntity
import com.ettore.musicdrive.data.local.room.AlbumWithTracks
import com.ettore.musicdrive.data.local.room.LibraryDao
import com.ettore.musicdrive.data.local.room.TrackEntity
import com.ettore.musicdrive.data.source.MusicSource
import com.ettore.musicdrive.data.source.SourceType
import com.ettore.musicdrive.data.source.sourceTypeOfId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

fun AlbumWithTracks.toDriveAlbum() = DriveAlbum(
    id = album.id,
    name = album.name,
    artistHint = album.artistHint,
    tracks = tracks.map { DriveAudioFile(id = it.id, name = it.name, mimeType = it.mimeType, sizeBytes = it.sizeBytes) },
)

private fun DriveAlbum.toAlbumEntity(sourceType: SourceType, rootId: String) =
    AlbumEntity(id = id, name = name, artistHint = artistHint, sourceType = sourceType.name, rootId = rootId)

private fun DriveAudioFile.toTrackEntity(albumId: String) =
    TrackEntity(id = id, albumId = albumId, name = name, mimeType = mimeType, sizeBytes = sizeBytes)

// Same extension list as ui/TextFormatting.kt's withoutAudioExtension() - duplicated rather than
// imported from the ui package to keep this data-layer file free of a ui dependency for one
// trivial regex.
private val audioExtensionPattern = Regex("""\.(mp3|flac|m4a|aac|wav|ogg|opus|wma)$""", RegexOption.IGNORE_CASE)

private fun normalizeForMatch(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

private fun albumMatchKey(album: DriveAlbum): String =
    normalizeForMatch(album.artistHint.orEmpty()) + "|" + normalizeForMatch(album.name)

private fun trackMatchKey(track: DriveAudioFile): String =
    normalizeForMatch(audioExtensionPattern.replace(track.name, ""))

/**
 * Merges same-named albums across sources into one tile, local taking precedence: within a
 * matched album, a local track always displays instead of its Drive counterpart (a Drive track
 * only fills a gap when no local track matches it) - see docs/multi-source-plan.md §6. Matching
 * is exact-after-normalization (case/whitespace/punctuation-insensitive), not fuzzy - simple, and
 * consistent with this codebase's existing normalization style elsewhere (AlbumArtRepository's
 * isPlausibleTagValue / resolveArtistArt's filename sanitizing).
 *
 * Deliberately does NO re-sorting/interleaving of a merged album's tracks: the unioned list is
 * handed to AlbumArtRepository.resolveTrackOrder exactly like any other album's tracks, which
 * already resolves each track's real running order from THAT TRACK'S OWN embedded tag - since
 * compound ids never collide across sources, local- and Drive-origin tracks interleave correctly
 * by real track number for free, with no special-casing needed here.
 *
 * Known, accepted limitations (see the design doc): a Drive download for a song later superseded
 * by a matching local copy becomes invisible-but-still-stored (not solved here); play counts for
 * the "same" song played via both sources don't combine (two different compound ids).
 */
fun mergeSources(albums: List<DriveAlbum>): List<DriveAlbum> {
    val local = albums.filter { it.id.sourceTypeOfId() == SourceType.LOCAL }
    val others = albums.filter { it.id.sourceTypeOfId() != SourceType.LOCAL }
    if (local.isEmpty() || others.isEmpty()) return albums

    val localByKey = local.associateBy(::albumMatchKey)
    val mergedByLocalId = mutableMapOf<String, DriveAlbum>()
    val unmatchedOthers = mutableListOf<DriveAlbum>()

    others.forEach { other ->
        val match = localByKey[albumMatchKey(other)]
        if (match == null) {
            unmatchedOthers += other
        } else {
            val base = mergedByLocalId[match.id] ?: match
            val localTrackKeys = base.tracks.map(::trackMatchKey).toSet()
            val extraTracks = other.tracks.filter { trackMatchKey(it) !in localTrackKeys }
            mergedByLocalId[match.id] = base.copy(tracks = base.tracks + extraTracks)
        }
    }

    val finalLocal = local.map { mergedByLocalId[it.id] ?: it }
    return finalLocal + unmatchedOthers
}

/**
 * Combines the Room-cached index of every enabled source with live fetches: the library browses
 * instantly from cache, then quietly refreshes each active source in the background so it stays
 * in sync with what's really on Drive/the local folder. Which source(s) are active is read live
 * from [settingsRepository] on every call - see docs/multi-source-plan.md §2/§6.
 */
class LibraryRepository(
    private val sources: Map<SourceType, MusicSource>,
    private val settingsRepository: SettingsRepository,
    private val libraryDao: LibraryDao,
) {

    /** (source, rootId) for every currently enabled/configured source - 0, 1, or 2 entries. */
    private suspend fun activeRoots(): List<Pair<SourceType, String>> {
        val roots = mutableListOf<Pair<SourceType, String>>()
        if (settingsRepository.cloudProvider.first() == CloudProvider.GOOGLE_DRIVE) {
            settingsRepository.driveRootFolderId.first()?.let { roots += SourceType.DRIVE to it }
        }
        if (settingsRepository.localFolderEnabled.first()) {
            settingsRepository.localFolderTreeUri.first()?.let { roots += SourceType.LOCAL to it }
        }
        return roots
    }

    private suspend fun cachedAlbums(sourceType: SourceType, rootId: String): List<DriveAlbum> =
        libraryDao.getAlbumsWithTracks(sourceType.name, rootId).map { it.toDriveAlbum() }

    private suspend fun refreshSource(sourceType: SourceType, rootId: String): Result<List<DriveAlbum>> =
        sources.getValue(sourceType).listLibraryAlbums(rootId).onSuccess { albums ->
            libraryDao.replaceLibraryForSource(
                sourceType.name,
                rootId,
                albums.map { it.toAlbumEntity(sourceType, rootId) },
                albums.flatMap { album -> album.tracks.map { it.toTrackEntity(album.id) } },
            )
        }

    /**
     * Emits the merged cached library immediately if there is one, then emits again as each
     * active source's live fetch completes - a background-refresh failure for one source is
     * swallowed (not emitted) as long as SOME cached data is already showing, same "don't kick
     * the user from a stale-but-browsable library to an error screen" policy as before, just
     * evaluated per source now instead of globally.
     */
    fun loadLibrary(): Flow<Result<List<DriveAlbum>>> = flow {
        val roots = activeRoots()
        if (roots.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }

        val latest = mutableMapOf<SourceType, List<DriveAlbum>>()
        for ((type, rootId) in roots) {
            latest[type] = cachedAlbums(type, rootId)
        }
        if (latest.values.any { it.isNotEmpty() }) {
            emit(Result.success(mergeSources(latest.values.flatten())))
        }

        var anySucceeded = false
        var lastFailure: Throwable? = null
        for ((type, rootId) in roots) {
            refreshSource(type, rootId).fold(
                onSuccess = { albums ->
                    anySucceeded = true
                    latest[type] = albums
                    emit(Result.success(mergeSources(latest.values.flatten())))
                },
                onFailure = { e -> lastFailure = e },
            )
        }
        if (!anySucceeded && latest.values.all { it.isEmpty() }) {
            lastFailure?.let { emit(Result.failure(it)) }
        }
    }

    /**
     * One-shot live-fetch-+-Room-write for every active source, without [loadLibrary]'s
     * "emit cache first" step - for an explicit user-triggered refresh (pull-to-refresh on an
     * album page). A source that fails to refresh falls back to its last-known Room cache rather
     * than dropping out of the merged result entirely.
     */
    suspend fun refreshLibrary(): Result<List<DriveAlbum>> {
        val roots = activeRoots()
        if (roots.isEmpty()) return Result.success(emptyList())

        val merged = mutableListOf<DriveAlbum>()
        var anyFailure: Throwable? = null
        for ((type, rootId) in roots) {
            refreshSource(type, rootId).fold(
                onSuccess = { merged += it },
                onFailure = { e ->
                    anyFailure = e
                    merged += cachedAlbums(type, rootId)
                },
            )
        }
        if (merged.isEmpty()) {
            anyFailure?.let { return Result.failure(it) }
        }
        return Result.success(mergeSources(merged))
    }

    /** Drops one source's cached rows - call when that source's own root/tree CHANGES (a different Drive folder or local SAF tree picked), not when a source is merely toggled off. */
    suspend fun clearSourceCache(sourceType: SourceType) {
        libraryDao.clearSource(sourceType.name)
    }
}
