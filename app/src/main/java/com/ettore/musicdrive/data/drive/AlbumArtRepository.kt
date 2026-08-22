package com.ettore.musicdrive.data.drive

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ettore.musicdrive.auth.DriveTokenProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves cover art for an album: an embedded picture in one of its tracks
 * first (extracted straight from the Drive stream, no separate storage of
 * "does this album have art" needed), falling back to the iTunes Search API
 * (free, no key) when nothing is embedded. Results are cached both in memory
 * for the process lifetime and on disk (embedded art only) so repeat visits
 * to the library don't re-fetch.
 *
 * The resolved value is whatever Coil's AsyncImage can load directly: a
 * [File] for cached embedded art, or a artwork URL [String] for the iTunes
 * fallback. Null means "no art found" (including previous failed attempts,
 * so we don't hammer the network every recomposition).
 */
class AlbumArtRepository(
    context: Context,
    private val tokenProvider: DriveTokenProvider,
) {
    private val diskCacheDir = File(context.cacheDir, "album_art").apply { mkdirs() }
    private val memoryCache = mutableMapOf<String, Any?>()

    suspend fun resolveArt(album: DriveAlbum): Any? = withContext(Dispatchers.IO) {
        memoryCache[album.id]?.let { return@withContext it }
        if (memoryCache.containsKey(album.id)) return@withContext null

        val cachedFile = File(diskCacheDir, "${album.id}.jpg")
        if (cachedFile.exists()) {
            memoryCache[album.id] = cachedFile
            return@withContext cachedFile
        }

        val embedded = extractEmbeddedArt(album)
        if (embedded != null) {
            runCatching { cachedFile.writeBytes(embedded) }
            memoryCache[album.id] = cachedFile
            return@withContext cachedFile
        }

        val artworkUrl = fetchItunesArtworkUrl(album)
        memoryCache[album.id] = artworkUrl
        artworkUrl
    }

    private suspend fun extractEmbeddedArt(album: DriveAlbum): ByteArray? {
        val track = album.tracks.firstOrNull() ?: return null
        val token = tokenProvider.getAccessToken().getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                "https://www.googleapis.com/drive/v3/files/${track.id}?alt=media",
                mapOf("Authorization" to "Bearer $token"),
            )
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun fetchItunesArtworkUrl(album: DriveAlbum): String? {
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
            val artworkUrl100 = results.getJSONObject(0).getString("artworkUrl100")
            artworkUrl100.replace("100x100bb", "600x600bb")
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
