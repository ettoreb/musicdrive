package com.ettore.musicdrive.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.ettore.musicdrive.MusicDriveApplication
import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.searchLibrary
import com.ettore.musicdrive.data.toDriveAlbum
import com.ettore.musicdrive.ui.UNKNOWN_ARTIST
import com.ettore.musicdrive.ui.groupByArtist
import com.ettore.musicdrive.ui.withoutAudioExtension
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

private const val ROOT_ID = "root"
private const val MOST_PLAYED_ID = "most_played"
private const val ALBUMS_ID = "albums"
private const val ARTISTS_ID = "artists"
private const val ALBUM_PREFIX = "album:"
private const val ARTIST_PREFIX = "artist:"
private const val MOST_PLAYED_LIMIT = 30

/**
 * The Android Auto / car head-unit browsing tree:
 *   root -> most_played (30 tracks, each queues its whole album)
 *        -> albums -> "album:{id}" -> tracks
 *        -> artists -> "artist:{name}" -> that artist's albums -> "album:{id}" -> tracks
 *
 * Reads Room directly (LibraryDao.getAlbumsWithTracks, a one-shot suspend call - cheap enough
 * at personal-library scale to re-query fresh on every callback, no extra caching layer) rather
 * than LibraryRepository's Flow, and never touches the Drive API - browsing is fully offline,
 * same cached data the phone UI already keeps warm.
 *
 * Errors (Room failure, no library root configured yet) degrade to an empty children list
 * rather than a LibraryResult error - a friendlier failure mode for a car UI. An unknown
 * parentId is the one real RESULT_ERROR_BAD_VALUE case.
 */
@Suppress("DEPRECATION") // setFolderType/FOLDER_TYPE_* are deprecated in favor of setMediaType, but
// still needed alongside it for older head units' legacy MediaBrowserCompat-based bridge.
@UnstableApi
class MusicLibrarySessionCallback(
    private val app: MusicDriveApplication,
    private val serviceScope: CoroutineScope,
) : MediaLibrarySession.Callback {

    // MediaBrowserServiceCompat clients (what Android Auto's head-unit actually binds through)
    // commonly call onGetSearchResult without a preceding onSearch, or with a different query -
    // cache the last query's results and recompute on a mismatch rather than assuming onSearch
    // always ran first.
    private var lastSearchQuery: String? = null
    private var lastSearchAlbums: List<DriveAlbum> = emptyList()
    private var lastSearchTracks: List<MediaItem> = emptyList()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = browsableItem(
            id = ROOT_ID,
            title = "MusicDrive",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            folderType = MediaMetadata.FOLDER_TYPE_MIXED,
        )
        return Futures.immediateFuture(LibraryResult.ofItem(root, null))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.toListenableFuture {
        val children = childrenFor(parentId)
            ?: return@toListenableFuture LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        LibraryResult.ofItemList(children.paged(page, pageSize), null)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.toListenableFuture {
        val item = when {
            mediaId == ROOT_ID -> browsableItem(ROOT_ID, "MusicDrive", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, MediaMetadata.FOLDER_TYPE_MIXED)
            mediaId == ALBUMS_ID -> albumsNode()
            mediaId == ARTISTS_ID -> artistsNode()
            mediaId == MOST_PLAYED_ID -> mostPlayedNode()
            mediaId.startsWith(ALBUM_PREFIX) -> loadAlbums().find { it.id == mediaId.removePrefix(ALBUM_PREFIX) }?.let(::albumNode)
            mediaId.startsWith(ARTIST_PREFIX) -> artistNode(Uri.decode(mediaId.removePrefix(ARTIST_PREFIX)))
            else -> locateTrack(mediaId)?.let { (album, track, index) -> playableTrackItem(track, album, index) }
        }
        if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = serviceScope.toListenableFuture {
        val results = loadAlbums().searchLibrary(query)
        lastSearchQuery = query
        lastSearchAlbums = results.albums
        lastSearchTracks = results.tracks.map { playableTrackItem(it.track, it.album, it.index) }
        val total = lastSearchAlbums.size + lastSearchTracks.size
        session.notifySearchResultChanged(browser, query, total, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.toListenableFuture {
        if (query != lastSearchQuery) {
            val results = loadAlbums().searchLibrary(query)
            lastSearchQuery = query
            lastSearchAlbums = results.albums
            lastSearchTracks = results.tracks.map { playableTrackItem(it.track, it.album, it.index) }
        }
        val combined = lastSearchAlbums.map(::albumNode) + lastSearchTracks
        LibraryResult.ofItemList(combined.paged(page, pageSize), null)
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = serviceScope.toListenableFuture {
        mediaItems.map { resolvePlayable(it) }
    }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.toListenableFuture {
        // A tap from the car (Albums/Artists/Most Played/Search) always arrives as a single bare
        // track id - expand it to its whole owning album, same as MainActivity.playAlbum, so
        // skip next/previous behave identically whichever surface started playback.
        if (mediaItems.size == 1) {
            locateTrack(mediaItems[0].mediaId)?.let { (album, _, index) ->
                val queue = album.tracks.mapIndexed { i, t -> resolvedTrackItem(t, album, i) }
                return@toListenableFuture MediaSession.MediaItemsWithStartPosition(queue, index, 0L)
            }
        }
        MediaSession.MediaItemsWithStartPosition(mediaItems.map { resolvePlayable(it) }, startIndex, startPositionMs)
    }

    // ---- data access -------------------------------------------------------------------

    private suspend fun loadAlbums(): List<DriveAlbum> {
        val rootFolderId = app.settingsRepository.libraryRootFolderId.first() ?: return emptyList()
        return try {
            app.database.libraryDao().getAlbumsWithTracks(rootFolderId).map { it.toDriveAlbum() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun locateTrack(trackId: String): Triple<DriveAlbum, DriveAudioFile, Int>? =
        findTrack(loadAlbums(), trackId)

    private fun findTrack(albums: List<DriveAlbum>, trackId: String): Triple<DriveAlbum, DriveAudioFile, Int>? {
        for (album in albums) {
            val index = album.tracks.indexOfFirst { it.id == trackId }
            if (index >= 0) return Triple(album, album.tracks[index], index)
        }
        return null
    }

    private suspend fun resolvePlayable(item: MediaItem): MediaItem =
        locateTrack(item.mediaId)?.let { (album, track, index) -> resolvedTrackItem(track, album, index) } ?: item

    // ---- browse-tree structure ----------------------------------------------------------

    private suspend fun childrenFor(parentId: String): List<MediaItem>? = when {
        parentId == ROOT_ID -> listOf(mostPlayedNode(), albumsNode(), artistsNode())
        parentId == MOST_PLAYED_ID -> mostPlayedChildren()
        parentId == ALBUMS_ID -> loadAlbums().map(::albumNode)
        parentId == ARTISTS_ID -> loadAlbums().groupByArtist().map { artistNode(it.name) }
        parentId.startsWith(ALBUM_PREFIX) -> albumChildren(parentId.removePrefix(ALBUM_PREFIX))
        parentId.startsWith(ARTIST_PREFIX) -> artistChildren(Uri.decode(parentId.removePrefix(ARTIST_PREFIX)))
        else -> null
    }

    private suspend fun mostPlayedChildren(): List<MediaItem> {
        val top = try {
            app.database.playCountDao().observeTopTracks(MOST_PLAYED_LIMIT).first()
        } catch (e: Exception) {
            emptyList()
        }
        val albums = loadAlbums()
        return top.mapNotNull { playCount ->
            findTrack(albums, playCount.trackId)?.let { (album, track, index) -> playableTrackItem(track, album, index) }
        }
    }

    private suspend fun albumChildren(albumId: String): List<MediaItem> {
        val album = loadAlbums().find { it.id == albumId } ?: return emptyList()
        return album.tracks.mapIndexed { index, track -> playableTrackItem(track, album, index) }
    }

    private suspend fun artistChildren(artistName: String): List<MediaItem> {
        val artist = loadAlbums().groupByArtist().find { it.name == artistName } ?: return emptyList()
        return artist.albums.map(::albumNode)
    }

    // ---- MediaItem builders ---------------------------------------------------------------

    private fun mostPlayedNode() = browsableItem(MOST_PLAYED_ID, "Most Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, MediaMetadata.FOLDER_TYPE_MIXED)
    private fun albumsNode() = browsableItem(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, MediaMetadata.FOLDER_TYPE_ALBUMS)
    private fun artistsNode() = browsableItem(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, MediaMetadata.FOLDER_TYPE_ARTISTS)

    private fun albumNode(album: DriveAlbum) = browsableItem(
        id = "$ALBUM_PREFIX${album.id}",
        title = album.name,
        subtitle = album.artistHint,
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        folderType = MediaMetadata.FOLDER_TYPE_TITLES,
    )

    private fun artistNode(name: String) = browsableItem(
        id = "$ARTIST_PREFIX${Uri.encode(name)}",
        title = name,
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        folderType = MediaMetadata.FOLDER_TYPE_ALBUMS,
    )

    private fun browsableItem(id: String, title: String, mediaType: Int, folderType: Int, subtitle: String? = null): MediaItem {
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setFolderType(folderType)
        if (subtitle != null) builder.setSubtitle(subtitle)
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(builder.build()).build()
    }

    /** A browse-tree track node: no URI. Only onSetMediaItems/onAddMediaItems resolve real playback. */
    private fun playableTrackItem(track: DriveAudioFile, album: DriveAlbum, index: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name.withoutAudioExtension())
            .setArtist(album.artistHint ?: UNKNOWN_ARTIST)
            .setAlbumTitle(album.name)
            .setTrackNumber(index + 1)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata).build()
    }

    /** Same shape as MainActivity.playAlbum's queue items - a real, directly playable MediaItem with a resolved Drive URI. */
    private fun resolvedTrackItem(track: DriveAudioFile, album: DriveAlbum, index: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name.withoutAudioExtension())
            .setArtist(album.artistHint ?: UNKNOWN_ARTIST)
            .setAlbumTitle(album.name)
            .setTrackNumber(index + 1)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(driveMediaUri(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun List<MediaItem>.paged(page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
        val fromIndex = (page * pageSize).coerceIn(0, size)
        val toIndex = (fromIndex + pageSize).coerceIn(fromIndex, size)
        return subList(fromIndex, toIndex)
    }
}
