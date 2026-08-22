package com.ettore.musicdrive.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Like Media3's LeastRecentlyUsedCacheEvictor, but the byte limit can be
 * changed live (e.g. from a settings slider) without recreating the player
 * or the underlying SimpleCache.
 */
@UnstableApi
class AdjustableLruEvictor(initialMaxBytes: Long) : CacheEvictor {

    @Volatile
    var maxBytes: Long = initialMaxBytes
        set(value) {
            field = value
            cache?.let { evictCache(it, 0) }
        }

    private val leastRecentlyUsed = TreeSet<CacheSpan> { lhs, rhs ->
        val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        if (delta == 0L) lhs.compareTo(rhs) else if (delta < 0) -1 else 1
    }

    private var cache: Cache? = null
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, currentPosition: Long, length: Long) {
        this.cache = cache
        evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }
}
