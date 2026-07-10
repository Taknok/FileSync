package com.kunzisoft.filesync.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FileMetadata::class, ProviderConfig::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metadataDao(): MetadataDao
    abstract fun providerDao(): ProviderDao
}
