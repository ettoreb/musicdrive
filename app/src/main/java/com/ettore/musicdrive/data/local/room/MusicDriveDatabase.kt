package com.ettore.musicdrive.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AlbumEntity::class, TrackEntity::class, LyricsEntity::class, PlayCountEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MusicDriveDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun playCountDao(): PlayCountDao
}
