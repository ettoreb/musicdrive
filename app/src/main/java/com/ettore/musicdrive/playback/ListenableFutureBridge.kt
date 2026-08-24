package com.ettore.musicdrive.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges a suspend block (Room/DataStore reads) into the ListenableFuture-based API
 * MediaLibrarySession.Callback requires. Guava's real SettableFuture/ListenableFuture are
 * already transitively on the classpath (via media3-session and google-api-client), so this
 * needs no new dependency.
 */
fun <T> CoroutineScope.toListenableFuture(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    launch {
        try {
            future.set(block())
        } catch (e: Throwable) {
            future.setException(e)
        }
    }
    return future
}
