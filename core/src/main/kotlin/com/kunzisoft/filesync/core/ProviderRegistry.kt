package com.kunzisoft.filesync.core

import com.kunzisoft.filesync.provider.api.RemoteProvider

interface ProviderRegistry {
    suspend fun getProviders(): List<Pair<String, RemoteProvider>>
    suspend fun getProvider(id: String): RemoteProvider?
    fun invalidateProvider(id: String)
}
