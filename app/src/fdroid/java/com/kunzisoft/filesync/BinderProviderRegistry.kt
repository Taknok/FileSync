package com.kunzisoft.filesync

import android.content.Context
import com.kunzisoft.filesync.core.ProviderRegistry
import com.kunzisoft.filesync.provider.api.RemoteProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderProviderRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) : ProviderRegistry {

    override suspend fun getProviders(): List<Pair<String, RemoteProvider>> {
        // Use PackageManager to discover services with intent-filter org.filesync.PROVIDER
        return emptyList()
    }

    override suspend fun getProvider(id: String): RemoteProvider? {
        // Bind to service and return AIDL wrapper
        return null
    }

    override fun invalidateProvider(id: String) {
        // Unbind or clear cache
    }
}
