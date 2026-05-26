package com.kunzisoft.filesync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.kunzisoft.filesync.core.ProviderRegistry
import com.kunzisoft.filesync.storage.FileMetadata
import com.kunzisoft.filesync.storage.MetadataDao
import com.kunzisoft.filesync.storage.ProviderDao
import com.kunzisoft.filesync.storage.SyncStatus
import android.provider.DocumentsContract

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val metadataDao: MetadataDao,
    private val providerDao: ProviderDao,
    private val providerRegistry: ProviderRegistry
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val providerId = inputData.getString("providerId") ?: return Result.failure()
        val path = inputData.getString("path") ?: "/"
        
        Log.d("FileSync", "SyncWorker: Starting sync for provider $providerId at path [$path]")

        val provider = providerRegistry.getProvider(providerId) ?: return Result.failure()
        
        return try {
            val remoteFiles = provider.list(path)
            
            val normalizedRequestedPath = path.trim('/')
            val parentDocumentId = if (normalizedRequestedPath.isEmpty()) "$providerId:/" else "$providerId:$normalizedRequestedPath"

            Log.d("FileSync", "SyncWorker: Normalizing for parent [$parentDocumentId]. Found ${remoteFiles.size} entries.")

            val localChildren = metadataDao.getChildren(parentDocumentId)
            val remotePathSet = remoteFiles.map { it.path.trim('/') }.toSet()

            // 1. Cleanup stale local files
            for (local in localChildren) {
                if (!remotePathSet.contains(local.path)) {
                    Log.d("FileSync", "SyncWorker: Deleting stale child [${local.path}] from parent [$parentDocumentId]")
                    metadataDao.delete(local)
                }
            }

            // 2. Upsert remote files
            for (remoteFile in remoteFiles) {
                val normalizedRemotePath = remoteFile.path.trim('/')
                
                // CRITICAL: WebDAV often returns the directory itself in the list. Skip it.
                if (normalizedRemotePath == normalizedRequestedPath) {
                    continue
                }

                val metadata = FileMetadata(
                    id = "$providerId:$normalizedRemotePath",
                    parentId = parentDocumentId,
                    providerId = providerId,
                    path = normalizedRemotePath,
                    name = remoteFile.name,
                    mimeType = remoteFile.mimeType,
                    size = remoteFile.size,
                    modifiedAt = remoteFile.modifiedAt,
                    isDirectory = remoteFile.isDirectory,
                    etag = remoteFile.etag,
                    syncStatus = SyncStatus.SYNCED
                )
                metadataDao.insert(metadata)
                Log.d("FileSync", "SyncWorker: Inserted [${metadata.id}] with parent [${metadata.parentId}]")
            }

            // Update throttle
            val config = providerDao.getProviderById(providerId)
            if (config != null) {
                providerDao.insertProvider(config.copy(lastSyncTime = System.currentTimeMillis()))
            }

            // Notify SAF
            val notifyUri = DocumentsContract.buildChildDocumentsUri("com.kunzisoft.filesync.documents", parentDocumentId)
            context.contentResolver.notifyChange(notifyUri, null)
            
            Result.success()
        } catch (e: Exception) {
            Log.e("FileSync", "SyncWorker: Error syncing $path", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }
}
