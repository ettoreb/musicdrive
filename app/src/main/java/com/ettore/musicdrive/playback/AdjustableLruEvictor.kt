package com.ettore.musicdrive.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/**
 * Evicts by play count, not recency: the least-played track's cached bytes go first,
 * ties broken by least-recently-touched. This is a deliberate departure from Media3's
 * stock LeastRecentlyUsedCacheEvictor - a song streamed once yesterday would outlive a
 * song streamed 50 times last week under pure LRU, which isn't what a "most-played"
 * personal library wants. The byte limit can also be changed live (e.g. from a settings
 * slider) without recreating the player or the underlying SimpleCache.
 */
@UnstableApi
class AdjustableLruEvictor(initialMaxBytes: Long) : CacheEvictor {

    @Volatile
    var maxBytes: Long = initialMaxBytes
        set(value) {
            field = value
            cache?.let { evictCache(it, 0) }
        }

    /**
     * trackId -> total play count, refreshed live from Room's PlayCountDao (see
     * MusicDriveApplication). A track missing from this map (never played, or the play
     * just landed and hasn't round-tripped through Room yet) counts as 0 - evicted first.
     */
    @Volatile
    var playCounts: Map<String, Int> = emptyMap()

    private val spans = mutableSetOf<CacheSpan>()
    private var cache: Cache? = null
    private var currentSize = 0L

    // The span currently being written by the active read/write session is protected from
    // eviction where possible (falling back to evicting it anyway if it's the only cached
    // content left) - otherwise a rarely-played track being streamed right now could evict
    // its own in-flight bytes mid-playback, since play-count alone doesn't know "active".
    private var activeKey: String? = null

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, currentPosition: Long, length: Long) {
        this.cache = cache
        activeKey = key
        evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        spans.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        if (currentSize + requiredSpace <= maxBytes) return
        val protectedActive = spans.filter { it.key != activeKey }
        val candidates = protectedActive.ifEmpty { spans.toList() }
            .sortedWith(compareBy<CacheSpan> { playCountOf(it) }.thenBy { it.lastTouchTimestamp })
            .iterator()
        while (currentSize + requiredSpace > maxBytes && candidates.hasNext()) {
            cache.removeSpan(candidates.next())
        }
    }

    private fun playCountOf(span: CacheSpan): Int {
        val trackId = trackIdRegex.find(span.key)?.groupValues?.get(1) ?: return 0
        return playCounts[trackId] ?: 0
    }

    private companion object {
        val trackIdRegex = Regex("""/files/([^/?]+)""")
    }
}
