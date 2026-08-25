package com.ettore.musicdrive.data.drive

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.local.room.AlbumYearDao
import com.ettore.musicdrive.data.local.room.AlbumYearEntity
import com.ettore.musicdrive.data.local.room.TrackOrderDao
import com.ettore.musicdrive.data.local.room.TrackOrderEntity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves cover art and release year for an album: embedded data in one of
 * its tracks first (extracted straight from the Drive stream), falling back
 * to the iTunes Search API (free, no key) when nothing is embedded. Art is
 * always written to disk (filesDir, not cacheDir, so it survives OS
 * cache-clearing the same way downloads do - covers should stay available
 * offline, not just until the OS decides to reclaim cache space) and
 * memory-cached for the process lifetime; year is cached in Room since it
 * needs to be known up front (for sorting) rather than lazily per visible
 * tile.
 *
 * The resolved art value is always a local [File] Coil's AsyncImage can load
 * directly - both the embedded and iTunes-fallback paths download once and
 * write to the same disk cache, so a track is never re-fetched over the
 * network after its first resolution. Null means "no art found" (including
 * previous failed attempts, so we don't hammer the network every
 * recomposition).
 */
class AlbumArtRepository(
    context: Context,
    private val tokenProvider: DriveTokenProvider,
    private val yearDao: AlbumYearDao,
    private val trackOrderDao: TrackOrderDao,
) {
    private val diskCacheDir = File(context.filesDir, "album_art").apply { mkdirs() }
    private val artistArtDiskCacheDir = File(context.filesDir, "artist_art").apply { mkdirs() }
    private val artMemoryCache = mutableMapOf<String, File?>()
    private val artistArtMemoryCache = mutableMapOf<String, File?>()
    private val yearMemoryCache = mutableMapOf<String, Int?>()

    /**
     * Total disk space used by cached album art - shown as an info line in Settings. Not
     * counted against the user's "Storage" limit (that governs the streaming cache + downloads
     * only): art is tiny for a personal library and always kept, so bounding/evicting it would
     * hurt browsing UX for negligible space savings.
     */
    suspend fun diskUsageBytes(): Long = withContext(Dispatchers.IO) {
        diskCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun resolveArt(album: DriveAlbum): File? = withContext(Dispatchers.IO) {
        artMemoryCache[album.id]?.let { return@withContext it }
        if (artMemoryCache.containsKey(album.id)) return@withContext null

        val cachedFile = File(diskCacheDir, "${album.id}.jpg")
        if (cachedFile.exists()) {
            artMemoryCache[album.id] = cachedFile
            return@withContext cachedFile
        }

        val embedded = extractEmbeddedPicture(album)
        if (embedded != null) {
            runCatching { cachedFile.writeBytes(embedded) }
            artMemoryCache[album.id] = cachedFile
            return@withContext cachedFile
        }

        val artworkUrl = fetchItunesResult(album)?.artworkUrl
        val downloaded = artworkUrl?.let { downloadBytes(it) }
        if (downloaded != null) {
            runCatching { cachedFile.writeBytes(downloaded) }
            artMemoryCache[album.id] = cachedFile
            return@withContext cachedFile
        }

        artMemoryCache[album.id] = null
        null
    }

    /**
     * Artist portrait, from the internet - there's no per-artist artwork concept in a Drive
     * library (unlike albums, an "artist" here is just a grouping of album folders, so there's
     * no embedded-tag source to try first). Uses the Deezer search API (free, no key) since
     * iTunes's musicArtist entity - already used for album art/year above - doesn't return an
     * artist image at all. Disk-cached (filesDir, survives OS cache-clearing, same as album art)
     * and memory-cached for the process lifetime, including a cached "not found" null.
     */
    suspend fun resolveArtistArt(artistName: String): File? = withContext(Dispatchers.IO) {
        artistArtMemoryCache[artistName]?.let { return@withContext it }
        if (artistArtMemoryCache.containsKey(artistName)) return@withContext null

        val safeName = artistName.replace(Regex("[^a-zA-Z0-9]"), "_")
        val cachedFile = File(artistArtDiskCacheDir, "$safeName.jpg")
        if (cachedFile.exists()) {
            artistArtMemoryCache[artistName] = cachedFile
            return@withContext cachedFile
        }

        val imageUrl = fetchDeezerArtistImageUrl(artistName)
        val downloaded = imageUrl?.let { downloadBytes(it) }
        if (downloaded != null) {
            runCatching { cachedFile.writeBytes(downloaded) }
            artistArtMemoryCache[artistName] = cachedFile
            return@withContext cachedFile
        }

        artistArtMemoryCache[artistName] = null
        null
    }

    private fun fetchDeezerArtistImageUrl(artistName: String): String? {
        val encodedQuery = URLEncoder.encode(artistName, "UTF-8")
        val url = URL("https://api.deezer.com/search/artist?q=$encodedQuery&limit=1")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).getJSONArray("data")
            if (results.length() == 0) return null
            val result = results.getJSONObject(0)
            result.optString("picture_xl").ifBlank { result.optString("picture_big") }.ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Release year, embedded tag first then iTunes fallback. Cached in Room so it survives process restarts and is known up front for sorting. */
    suspend fun resolveYear(album: DriveAlbum): Int? = withContext(Dispatchers.IO) {
        yearMemoryCache[album.id]?.let { return@withContext it }
        if (yearMemoryCache.containsKey(album.id)) return@withContext null

        yearDao.get(album.id)?.let { cached ->
            yearMemoryCache[album.id] = cached.year
            return@withContext cached.year
        }

        val year = extractEmbeddedYear(album) ?: fetchItunesResult(album)?.year
        yearDao.upsert(AlbumYearEntity(album.id, year))
        yearMemoryCache[album.id] = year
        year
    }

    /**
     * An album's tracks in their REAL running order, resolved from each track's own embedded
     * CD-track-number tag (ID3 TRCK or equivalent) rather than the Drive filename.
     * DriveRepository's filename-based sort (leading digits in the name) only works for
     * libraries ripped/named with a "01 - Song.mp3" convention; a library whose files are just
     * "Song Title.mp3" (confirmed live: Gorillaz's "Cracker Island" - no leading numbers at
     * all, some other libraries even have a song whose TITLE starts with a digit, e.g.
     * "26.mp3", "777.mp3", which the filename heuristic misreads as an actual track number)
     * has no other signal to sort by and falls back to plain alphabetical, which is not the
     * real tracklist order. This probes each track individually instead. Every result -
     * including "no tag found" - is cached per-track in Room, so this network round trip only
     * ever happens once per track, not once per album view; a subsequent open of the same
     * album is a single batched Room read with zero network calls. Bounded concurrency (see
     * the year-resolution comment on MainActivity's yearResolveSemaphore for why): opening a
     * MediaMetadataRetriever is a heavyweight binder-backed HTTP session, and firing one per
     * track unbounded for a 30-track album starves the binder thread pool the same way it did
     * for concurrent per-album year resolution.
     */
    suspend fun resolveTrackOrder(album: DriveAlbum): DriveAlbum = withContext(Dispatchers.IO) {
        val cached = trackOrderDao.getForTracks(album.tracks.map { it.id }).associateBy { it.trackId }
        val numbers = cached.mapValuesTo(mutableMapOf()) { it.value.trackNumber }

        val missing = album.tracks.filterNot { it.id in cached }
        if (missing.isNotEmpty()) {
            val semaphore = Semaphore(4)
            coroutineScope {
                missing.map { track ->
                    async {
                        semaphore.withPermit {
                            val number = extractEmbeddedTrackNumber(track)
                            trackOrderDao.upsert(TrackOrderEntity(track.id, number))
                            numbers[track.id] = number
                        }
                    }
                }.awaitAll()
            }
        }

        // Stable: a track with no resolved number (no tag, or the lookup failed) keeps its
        // original relative position instead of being shoved to one arbitrary end.
        val originalIndex = album.tracks.withIndex().associate { (i, t) -> t.id to i }
        val orderedTracks = album.tracks.sortedWith(
            compareBy({ numbers[it.id] ?: Int.MAX_VALUE }, { originalIndex.getValue(it.id) }),
        )
        album.copy(tracks = orderedTracks)
    }

    private suspend fun extractEmbeddedTrackNumber(track: DriveAudioFile): Int? {
        val token = tokenProvider.getAccessToken().getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                "https://www.googleapis.com/drive/v3/files/${track.id}?alt=media",
                mapOf("Authorization" to "Bearer $token"),
            )
            // Tags sometimes encode "track/total" (e.g. "3/12") - only the track half matters here.
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private suspend fun openRetriever(album: DriveAlbum): MediaMetadataRetriever? {
        val track = album.tracks.firstOrNull() ?: return null
        val token = tokenProvider.getAccessToken().getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                "https://www.googleapis.com/drive/v3/files/${track.id}?alt=media",
                mapOf("Authorization" to "Bearer $token"),
            )
            retriever
        } catch (e: Exception) {
            retriever.release()
            null
        }
    }

    private suspend fun extractEmbeddedPicture(album: DriveAlbum): ByteArray? {
        val retriever = openRetriever(album) ?: return null
        return try {
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private suspend fun extractEmbeddedYear(album: DriveAlbum): Int? {
        val retriever = openRetriever(album) ?: return null
        return try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private data class ItunesResult(val artworkUrl: String?, val year: Int?)

    private fun fetchItunesResult(album: DriveAlbum): ItunesResult? {
        val query = listOfNotNull(album.artistHint, album.name).joinToString(" ")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://itunes.apple.com/search?entity=album&limit=1&term=$encodedQuery")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).getJSONArray("results")
            if (results.length() == 0) return null
            val result = results.getJSONObject(0)
            val artworkUrl = result.getString("artworkUrl100").replace("100x100bb", "600x600bb")
            val year = result.optString("releaseDate", "").take(4).toIntOrNull()
            ItunesResult(artworkUrl, year)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
