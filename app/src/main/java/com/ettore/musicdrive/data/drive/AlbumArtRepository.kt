package com.ettore.musicdrive.data.drive

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.data.local.room.AlbumYearDao
import com.ettore.musicdrive.data.local.room.AlbumYearEntity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
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
