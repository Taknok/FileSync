package com.kunzisoft.filesync

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@HiltAndroidApp
class FileSyncApplication : Application(), Configuration.Provider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HiltWorkerFactoryEntryPoint {
        fun workerFactory(): HiltWorkerFactory
    }

    override val workManagerConfiguration: Configuration
        get() {
            Log.d("FileSync", "WorkManager custom configuration requested")
            val factory = EntryPointAccessors.fromApplication(
                this,
                HiltWorkerFactoryEntryPoint::class.java
            ).workerFactory()

            return Configuration.Builder()
                .setWorkerFactory(factory)
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        Log.d("FileSync", "Application onCreate called")
    }
}
