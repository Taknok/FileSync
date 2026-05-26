package com.kunzisoft.filesync

import com.kunzisoft.filesync.core.ProviderRegistry
import com.kunzisoft.filesync.provider.api.RemoteProvider
import com.kunzisoft.filesync.provider.webdav.WebDavProvider
import com.kunzisoft.filesync.storage.ProviderDao
import com.kunzisoft.filesync.storage.EncryptedStorage
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class InternalProviderRegistry @Inject constructor(
    private val providerDao: ProviderDao,
    private val encryptedStorage: EncryptedStorage,
    private val okHttpClient: OkHttpClient
) : ProviderRegistry {

    private val activeProviders = mutableMapOf<String, RemoteProvider>()

    override suspend fun getProviders(): List<Pair<String, RemoteProvider>> = withContext(Dispatchers.IO) {
        // Implementation for listing all providers
        activeProviders.toList()
    }

    override suspend fun getProvider(id: String): RemoteProvider? = withContext(Dispatchers.IO) {
        Log.d("FileSync", "Registry: looking for provider $id")
        activeProviders[id] ?: instantiateProvider(id)
    }

    override fun invalidateProvider(id: String) {
        Log.d("FileSync", "Registry: Invalidating provider $id")
        activeProviders.remove(id)
    }

    private suspend fun instantiateProvider(id: String): RemoteProvider? {
        val config = providerDao.getProviderById(id)
        if (config == null) {
            Log.e("FileSync", "Registry: Provider $id not found in DB")
            return null
        }
        if (!config.isEnabled) {
            Log.w("FileSync", "Registry: Provider $id is disabled")
            return null
        }

        Log.d("FileSync", "Registry: Instantiating provider ${config.name} (${config.type})")
        val provider = when (config.type) {
            "webdav" -> WebDavProvider(
                client = okHttpClient,
                baseUrl = config.baseUrl ?: "",
                user = config.username,
                pass = encryptedStorage.getCredential("password_${config.id}")
            )
            else -> null
        }
        
        provider?.let { activeProviders[id] = it }
        return provider
    }
}
