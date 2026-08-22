package com.ettore.musicdrive.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AlbumEntity::class, TrackEntity::class, LyricsEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MusicDriveDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun lyricsDao(): LyricsDao
}
