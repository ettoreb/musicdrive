package com.ettore.musicdrive.data

import com.ettore.musicdrive.data.local.room.PlayCountDao
import com.ettore.musicdrive.data.local.room.PlayCountEntity
import kotlinx.coroutines.flow.Flow

class PlayStatsRepository(private val playCountDao: PlayCountDao) {

    fun observeTopTracks(limit: Int): Flow<List<PlayCountEntity>> = playCountDao.observeTopTracks(limit)

    suspend fun recordPlay(trackId: String) {
        playCountDao.incrementPlay(trackId, System.currentTimeMillis())
    }
}
