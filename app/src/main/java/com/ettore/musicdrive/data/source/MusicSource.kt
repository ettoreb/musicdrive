package com.ettore.musicdrive.data.source

import android.media.MediaMetadataRetriever
import com.ettore.musicdrive.data.drive.DriveAlbum

/**
 * One music provider: Google Drive today, a local on-device SAF folder, and a future cloud
 * provider slot (never two cloud providers active together - see docs/multi-source-plan.md).
 * Every track/album id this app hands around is globally unique across sources by construction
 * (see [compoundId]), so nothing above this interface (Room, MediaItem.mediaId,
 * DownloadRequest.id, PlayCountEntity) needs to know which source a given id came from - only the
 * few places that actually need to ADDRESS the file (playback URI, MediaMetadataRetriever) look
 * the source back up via [sourceTypeOfId] and dispatch through this interface.
 *
 * [DriveAlbum]/[com.ettore.musicdrive.data.drive.DriveAudioFile] are reused as the shared
 * model for every source (not renamed to something source-neutral) - a deliberate choice to
 * avoid a large, purely-cosmetic rename sweep across every UI file that already consumes them.
 */
interface MusicSource {
    val type: SourceType

    /** False for local files: no auth/network layer needed at all for playback or metadata reads. */
    val requiresNetworkAuth: Boolean

    /** Every album found under [rootId] (a Drive folder id, or a local SAF tree Uri string), each with its tracks - ids already compound (see [SourceType.compoundId]). */
    suspend fun listLibraryAlbums(rootId: String): Result<List<DriveAlbum>>

    /** The playable URI for a track, given its RAW (not compound) id. */
    fun mediaUri(rawTrackId: String): String

    /**
     * Opens a MediaMetadataRetriever positioned at this track (RAW id), however this source needs
     * to (Bearer header vs. context+content-Uri) - used by AlbumArtRepository for art/year/tags/
     * track-order. Null if the track can't currently be opened (network failure, revoked SAF
     * grant, etc).
     */
    suspend fun openRetriever(rawTrackId: String): MediaMetadataRetriever?
}
