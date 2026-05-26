package com.kunzisoft.filesync.di

import com.kunzisoft.filesync.core.ProviderRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    @Binds
    @Singleton
    abstract fun bindProviderRegistry(
        registry: com.kunzisoft.filesync.InternalProviderRegistry
    ): ProviderRegistry
}
