package com.kunzisoft.filesync.di

import android.content.Context
import androidx.room.Room
import com.kunzisoft.filesync.storage.AppDatabase
import com.kunzisoft.filesync.storage.MetadataDao
import com.kunzisoft.filesync.storage.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import androidx.work.WorkManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "filesync.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideMetadataDao(db: AppDatabase): MetadataDao = db.metadataDao()

    @Provides
    fun provideProviderDao(db: AppDatabase): ProviderDao = db.providerDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
