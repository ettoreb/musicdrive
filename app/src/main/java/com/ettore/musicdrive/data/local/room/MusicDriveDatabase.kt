package com.ettore.musicdrive.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumEntity::class,
        TrackEntity::class,
        LyricsEntity::class,
        PlayCountEntity::class,
        AlbumYearEntity::class,
        TrackOrderEntity::class,
        AlbumTagsEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class MusicDriveDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun albumYearDao(): AlbumYearDao
    abstract fun trackOrderDao(): TrackOrderDao
    abstract fun albumTagsDao(): AlbumTagsDao
}
