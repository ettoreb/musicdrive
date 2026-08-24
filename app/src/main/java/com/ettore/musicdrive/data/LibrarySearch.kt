package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.drive.DriveAlbum
import com.ettore.musicdrive.data.drive.DriveAudioFile

data class TrackMatch(val album: DriveAlbum, val track: DriveAudioFile, val index: Int)

data class LibrarySearchResults(val albums: List<DriveAlbum>, val tracks: List<TrackMatch>)

/**
 * Substring match on album name/artistHint/track name, shared by the phone's SearchScreen and
 * Android Auto's onSearch/onGetSearchResult so the two don't drift into different behavior.
 * Filters the already-loaded library in memory - a personal-sized collection doesn't need a
 * separate search index or Drive query.
 */
fun List<DriveAlbum>.searchLibrary(query: String): LibrarySearchResults {
    if (query.isBlank()) return LibrarySearchResults(emptyList(), emptyList())

    val albums = filter {
        it.name.contains(query, ignoreCase = true) || it.artistHint?.contains(query, ignoreCase = true) == true
    }
    val tracks = flatMap { album ->
        album.tracks.mapIndexedNotNull { index, track ->
            if (track.name.contains(query, ignoreCase = true)) TrackMatch(album, track, index) else null
        }
    }.take(50)

    return LibrarySearchResults(albums, tracks)
}
