package com.kunzisoft.filesync

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kunzisoft.filesync.core.ProviderRegistry
import com.kunzisoft.filesync.storage.FileMetadata
import com.kunzisoft.filesync.storage.MetadataDao
import com.kunzisoft.filesync.storage.ProviderDao
import com.kunzisoft.filesync.storage.SyncStatus
import com.kunzisoft.filesync.sync.SyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

class FileSyncDocumentProvider : DocumentsProvider() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context?.applicationContext ?: throw IllegalStateException("Context not available"),
            ProviderEntryPoint::class.java
        )
    }

    private val metadataDao by lazy { entryPoint.metadataDao() }
    private val providerDao by lazy { entryPoint.providerDao() }
    private val workManager by lazy { entryPoint.workManager() }
    private val providerRegistry by lazy { entryPoint.providerRegistry() }
    private val notificationHelper by lazy { entryPoint.notificationHelper() }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val providers = runBlocking { providerDao.getAllProviders().first() }
        for (config in providers) {
            cursor.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, config.id)
                add(DocumentsContract.Root.COLUMN_SUMMARY, config.baseUrl)
                add(DocumentsContract.Root.COLUMN_TITLE, config.name)
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "${config.id}:/")
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == null) return cursor

        val providerId = documentId.substringBefore(":")
        val path = documentId.substringAfter(":", "/")

        val metadata = runBlocking { metadataDao.getById(documentId) }
        if (metadata != null) {
            addDocumentRow(cursor, metadata)
        } else {
            // Handle root or missing
            cursor.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (path == "/") providerId else path.substringAfterLast('/'))
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        Log.d("FileSync", "SAF: queryChildDocuments for $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (parentDocumentId == null) return cursor

        val notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        cursor.setNotificationUri(context?.contentResolver, notifyUri)

        val providerId = parentDocumentId.substringBefore(":")
        val path = parentDocumentId.substringAfter(":", "/")
        
        // Check per-folder sync time
        val folderMetadata = runBlocking { metadataDao.getById(parentDocumentId) }
        val lastSync = folderMetadata?.lastSyncTime ?: 0L
        val now = System.currentTimeMillis()
        
        // Force sync if folder is empty or more than 30 seconds old
        val children = runBlocking { metadataDao.getChildren(parentDocumentId) }
        
        if (children.isEmpty() || (now - lastSync > 30000)) {
            Log.d("FileSync", "SAF: Triggering sync for $parentDocumentId (last sync ${now - lastSync}ms ago, count ${children.size})")
            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf(
                    "providerId" to providerId,
                    "path" to path
                ))
                .build()
            workManager.enqueueUniqueWork("sync_${parentDocumentId.hashCode()}", ExistingWorkPolicy.KEEP, syncRequest)
        }

        for (child in children) {
            if (child.id != parentDocumentId) {
                addDocumentRow(cursor, child)
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d("FileSync", "SAF: openDocument $documentId mode $mode")
        if (documentId == null || mode == null) throw FileNotFoundException()

        val providerId = documentId.substringBefore(":")
        val path = documentId.substringAfter(":", "")
        val provider = runBlocking { providerRegistry.getProvider(providerId) } ?: throw FileNotFoundException()

        val cacheFile = File(context?.cacheDir, "cache_${documentId.hashCode()}")
        val isWrite = mode.contains("w")

        // 1. Conflict Check (for write mode)
        if (isWrite) {
            val cachedMetadata = runBlocking { metadataDao.getById(documentId) }
            val remoteMetadata = runBlocking { provider.stat(path) }

            if (remoteMetadata != null && cachedMetadata != null && cachedMetadata.etag != null) {
                if (remoteMetadata.etag != cachedMetadata.etag) {
                    Log.w("FileSync", "SAF: Conflict detected for $documentId.")
                    if (cachedMetadata.syncStatus != SyncStatus.CONFLICT) {
                        runBlocking { metadataDao.updateSyncStatus(documentId, SyncStatus.CONFLICT) }
                        notificationHelper.showConflictNotification(cachedMetadata.name)
                    }
                    throw IOException("Conflict detected: Remote file has changed since last sync.")
                }
            }
        }

        // 2. Download to cache (if reading or if cache is missing/stale)
        // For simplicity, we always download if not writing, or if opening for write but not truncating
        val isTruncate = mode.contains("t")
        if (!isTruncate) {
             runBlocking(Dispatchers.IO) {
                 try {
                     provider.read(path).use { input ->
                         cacheFile.outputStream().use { output ->
                             input.copyTo(output)
                         }
                     }
                     Log.d("FileSync", "SAF: Cached file for $documentId (${cacheFile.length()} bytes)")
                 } catch (e: Exception) {
                     if (!isWrite) throw FileNotFoundException("Could not download file: ${e.message}")
                     // For write, it's okay if it fails (new file)
                     if (!cacheFile.exists()) cacheFile.createNewFile()
                 }
             }
        } else {
            if (cacheFile.exists()) cacheFile.delete()
            cacheFile.createNewFile()
        }

        // 3. Return standard PFD with OnCloseListener to trigger upload
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.parseMode(mode),
            Handler(Looper.getMainLooper())
        ) {
            if (isWrite) {
                Log.d("FileSync", "SAF: File closed for $documentId, starting upload")
                thread {
                    try {
                        cacheFile.inputStream().use { input ->
                            val updatedRemote = runBlocking(Dispatchers.IO) {
                                provider.write(path, input, overwrite = true)
                            }
                            Log.d("FileSync", "SAF: Upload finished for $documentId")
                            runBlocking {
                                metadataDao.updateEtagAndSynced(documentId, updatedRemote?.etag)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FileSync", "SAF: Upload failed after close for $documentId", e)
                    } finally {
                        // Keep cache for next open? Or delete? 
                        // Better to delete to ensure fresh download next time
                        cacheFile.delete()
                    }
                }
            }
        }
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        if (parentDocumentId == null || mimeType == null || displayName == null) throw FileNotFoundException()

        val providerId = parentDocumentId.substringBefore(":")
        val parentPath = parentDocumentId.substringAfter(":", "").trim('/')
        val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        val documentId = "$providerId:$newPath"

        val provider = runBlocking { providerRegistry.getProvider(providerId) } ?: throw FileNotFoundException()

        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            runBlocking { provider.mkdir(newPath) }
        } else {
            // Create empty file
            runBlocking { provider.write(newPath, "".byteInputStream(), overwrite = true) }
        }

        // Add to local DB so it's visible immediately
        val metadata = FileMetadata(
            id = documentId,
            parentId = parentDocumentId,
            providerId = providerId,
            path = newPath,
            name = displayName,
            mimeType = mimeType,
            size = 0,
            modifiedAt = System.currentTimeMillis(),
            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
            etag = null
        )
        runBlocking { metadataDao.insert(metadata) }

        return documentId
    }

    override fun deleteDocument(documentId: String?) {
        if (documentId == null) return
        val providerId = documentId.substringBefore(":")
        val path = documentId.substringAfter(":", "")
        val provider = runBlocking { providerRegistry.getProvider(providerId) } ?: throw FileNotFoundException()

        runBlocking { 
            provider.delete(path)
            val metadata = metadataDao.getById(documentId)
            if (metadata != null) metadataDao.delete(metadata)
        }
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        if (documentId == null || displayName == null) throw FileNotFoundException()
        val providerId = documentId.substringBefore(":")
        val path = documentId.substringAfter(":", "")
        val parentPath = path.substringBeforeLast('/', "")
        val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        
        val provider = runBlocking { providerRegistry.getProvider(providerId) } ?: throw FileNotFoundException()
        runBlocking { 
            provider.move(path, newPath)
            // Update DB
            val oldMetadata = metadataDao.getById(documentId)
            if (oldMetadata != null) {
                metadataDao.delete(oldMetadata)
                metadataDao.insert(oldMetadata.copy(
                    id = "$providerId:$newPath",
                    path = newPath,
                    name = displayName
                ))
            }
        }
        return "$providerId:$newPath"
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        return documentId.startsWith(parentDocumentId)
    }

    private fun addDocumentRow(cursor: MatrixCursor, metadata: FileMetadata) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, metadata.id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, metadata.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, metadata.mimeType)
            add(DocumentsContract.Document.COLUMN_SIZE, metadata.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, metadata.modifiedAt)
            add(DocumentsContract.Document.COLUMN_FLAGS, 
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or 
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                (if (metadata.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
            )
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun metadataDao(): MetadataDao
        fun providerDao(): ProviderDao
        fun workManager(): WorkManager
        fun providerRegistry(): ProviderRegistry
        fun notificationHelper(): NotificationHelper
    }

    companion object {
        const val AUTHORITY = "com.kunzisoft.filesync.documents"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }
}
