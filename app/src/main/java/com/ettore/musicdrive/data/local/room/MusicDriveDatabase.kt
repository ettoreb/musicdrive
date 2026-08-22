package com.ettore.musicdrive.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AlbumEntity::class, TrackEntity::class], version = 1, exportSchema = false)
abstract class MusicDriveDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
