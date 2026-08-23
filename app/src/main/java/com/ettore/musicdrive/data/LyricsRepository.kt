package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.local.room.LyricsDao
import com.ettore.musicdrive.data.local.room.LyricsEntity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LyricsResult(
    val lyrics: String?,
    val isInstrumental: Boolean,
    val source: String,
    /** Raw LRC-format text (`[mm:ss.xx]line`), when LRCLIB has it. Null for embedded lyrics or plain-only LRCLIB results. */
    val syncedLyrics: String? = null,
)

/**
 * Embedded lyrics (extracted live during playback from the track's own ID3
 * USLT frame) always win when present - the caller passes them in via
 * [getLyrics]'s embeddedLyrics param, since extraction is inherently tied to
 * the player, not something this repository fetches on its own. Otherwise
 * falls back to the LRCLIB API (free, no key). Every result (including "not
 * found") is cached in Room keyed by track id, so a track is only ever
 * looked up once.
 */
class LyricsRepository(private val lyricsDao: LyricsDao) {

    suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        embeddedLyrics: String?,
    ): LyricsResult = withContext(Dispatchers.IO) {
        if (embeddedLyrics != null) {
            val result = LyricsResult(lyrics = embeddedLyrics, isInstrumental = false, source = "embedded")
            lyricsDao.upsert(LyricsEntity(trackId, result.lyrics, result.source, result.isInstrumental, result.syncedLyrics))
            return@withContext result
        }

        lyricsDao.get(trackId)?.let { cached ->
            return@withContext LyricsResult(cached.lyrics, cached.isInstrumental, cached.source, cached.syncedLyrics)
        }

        val result = fetchFromLrcLib(title, artist, album, durationMs)
            ?: LyricsResult(lyrics = null, isInstrumental = false, source = "none")
        lyricsDao.upsert(LyricsEntity(trackId, result.lyrics, result.source, result.isInstrumental, result.syncedLyrics))
        result
    }

    private fun fetchFromLrcLib(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        val query = URLEncoder.encode("$artist $title", "UTF-8")
        val encodedAlbum = URLEncoder.encode(album, "UTF-8")
        val url = URL("https://lrclib.net/api/search?q=$query&album_name=$encodedAlbum")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(body)
            if (results.length() == 0) return null

            val durationSec = durationMs / 1000.0
            val chosen = findByDuration(results, durationSec) ?: findFirstWithLyrics(results) ?: return null

            val plainLyrics = chosen.optString("plainLyrics", "").ifBlank { null }
            val syncedLyrics = chosen.optString("syncedLyrics", "").ifBlank { null }
            val instrumental = chosen.optBoolean("instrumental", false)
            if (plainLyrics == null && !instrumental) return null
            LyricsResult(lyrics = plainLyrics, isInstrumental = instrumental, source = "lrclib", syncedLyrics = syncedLyrics)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findByDuration(results: JSONArray, durationSec: Double): JSONObject? {
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            if (abs(candidate.optDouble("duration", Double.MAX_VALUE) - durationSec) < 2.0) {
                return candidate
            }
        }
        return null
    }

    private fun findFirstWithLyrics(results: JSONArray): JSONObject? {
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            if (candidate.optString("plainLyrics", "").isNotBlank()) {
                return candidate
            }
        }
        return null
    }
}
