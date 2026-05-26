package com.kunzisoft.filesync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.kunzisoft.filesync.core.ProviderRegistry
import com.kunzisoft.filesync.storage.EncryptedStorage
import com.kunzisoft.filesync.storage.ProviderConfig
import com.kunzisoft.filesync.storage.ProviderDao
import com.kunzisoft.filesync.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProviderViewModel @Inject constructor(
    private val providerDao: ProviderDao,
    private val encryptedStorage: EncryptedStorage,
    private val workManager: WorkManager,
    private val providerRegistry: ProviderRegistry
) : ViewModel() {

    private val _refreshEvents = MutableSharedFlow<RefreshResult>()
    val refreshEvents: SharedFlow<RefreshResult> = _refreshEvents

    val providers = providerDao.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    sealed class RefreshResult {
        data class Success(val providerName: String) : RefreshResult()
        data class Failure(val providerName: String, val message: String) : RefreshResult()
    }

    fun addProvider(name: String, url: String, user: String, pass: String) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val config = ProviderConfig(
                id = id,
                type = "webdav",
                name = name,
                baseUrl = url,
                username = user,
                isEnabled = true
            )
            encryptedStorage.saveCredential("password_$id", pass)
            providerDao.insertProvider(config)
        }
    }

    fun updateProvider(config: ProviderConfig, pass: String?) {
        viewModelScope.launch {
            providerDao.insertProvider(config) // Room uses @Insert(onConflict = REPLACE)
            pass?.let {
                encryptedStorage.saveCredential("password_${config.id}", it)
            }
            providerRegistry.invalidateProvider(config.id)
        }
    }

    fun forceRefresh(config: ProviderConfig) {
        viewModelScope.launch {
            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("providerId" to config.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            workManager.enqueueUniqueWork(
                "sync_${config.id}",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            // Observe the actual work status
            workManager.getWorkInfoByIdFlow(syncRequest.id).collect { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _refreshEvents.emit(RefreshResult.Success(config.name))
                        }
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString("error") ?: "Unknown error"
                            _refreshEvents.emit(RefreshResult.Failure(config.name, error))
                        }
                        else -> {} // Cancelled or other terminal states
                    }
                    // Stop collecting once finished
                    return@collect
                }
            }
        }
    }

    fun deleteProvider(config: ProviderConfig) {
        viewModelScope.launch {
            providerDao.deleteProvider(config)
            providerRegistry.invalidateProvider(config.id)
        }
    }
}
