package com.kunzisoft.filesync

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.concurrent.thread

class FileSyncDocumentProvider : DocumentsProvider() {

    private lateinit var metadataDao: MetadataDao
    private lateinit var providerDao: ProviderDao
    private lateinit var workManager: WorkManager
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            ProviderEntryPoint::class.java
        )
        metadataDao = entryPoint.metadataDao()
        providerDao = entryPoint.providerDao()
        workManager = entryPoint.workManager()
        providerRegistry = entryPoint.providerRegistry()
        notificationHelper = entryPoint.notificationHelper()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        
        val providers = runBlocking { providerDao.getAllProviders().first() }
        
        for (provider in providers) {
            val row = cursor.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, provider.id)
            row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            row.add(DocumentsContract.Root.COLUMN_TITLE, provider.name)
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "${provider.id}:/")
        }
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val path = documentId?.substringAfter(":", "") ?: return cursor
        
        val metadata = runBlocking { metadataDao.getById(documentId!!) }
        metadata?.let { addDocumentRow(cursor, it) } ?: run {
            val row = cursor.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, path.ifBlank { "/" })
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
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
        } else {
            Log.d("FileSync", "SAF: Using cached data for $parentDocumentId")
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

        if (mode.contains("w")) {
            // Conflict check before opening for write
            val cachedMetadata = runBlocking { metadataDao.getById(documentId) }
            val remoteMetadata = runBlocking { provider.stat(path) }

            if (remoteMetadata != null && cachedMetadata != null && cachedMetadata.etag != null) {
                if (remoteMetadata.etag != cachedMetadata.etag) {
                    Log.w("FileSync", "SAF: Conflict detected for $documentId. Remote: ${remoteMetadata.etag}, Local: ${cachedMetadata.etag}")
                    if (cachedMetadata.syncStatus != SyncStatus.CONFLICT) {
                        runBlocking { metadataDao.updateSyncStatus(documentId, SyncStatus.CONFLICT) }
                        notificationHelper.showConflictNotification(cachedMetadata.name)
                    }
                    throw IOException("Conflict detected: Remote file has changed since last sync.")
                }
            }

            val storageManager = context?.getSystemService(android.content.Context.STORAGE_SERVICE) as StorageManager
            val tempFile = File(context?.cacheDir, "upload_${documentId.hashCode()}.tmp")
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            
            // Pre-fill the temp file with current remote content if not truncating
            // This allows apps to read/append/seek correctly
            val shouldPreFill = (pfdMode and ParcelFileDescriptor.MODE_TRUNCATE) == 0
            
            if (shouldPreFill) {
                runBlocking {
                    try {
                        provider.read(path).use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d("FileSync", "SAF: Pre-filled temp file for $documentId (${tempFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.d("FileSync", "SAF: Could not pre-fill temp file: ${e.message}")
                        if (tempFile.exists()) tempFile.delete()
                        tempFile.createNewFile()
                    }
                }
            } else {
                Log.d("FileSync", "SAF: Truncate mode detected, starting with empty temp file for $documentId")
                if (tempFile.exists()) tempFile.delete()
                tempFile.createNewFile()
            }

            return storageManager.openProxyFileDescriptor(
                pfdMode,
                object : ProxyFileDescriptorCallback() {
                    private val raf = RandomAccessFile(tempFile, "rw")

                    override fun onGetSize(): Long = raf.length()

                    override fun onRead(offset: Long, size: Int, data: ByteArray?): Int {
                        if (data == null) return 0
                        raf.seek(offset)
                        val read = raf.read(data, 0, size)
                        return if (read < 0) 0 else read
                    }

                    override fun onWrite(offset: Long, size: Int, data: ByteArray?): Int {
                        if (data == null) return 0
                        raf.seek(offset)
                        raf.write(data, 0, size)
                        return size
                    }

                    override fun onFsync() {
                        raf.getFD().sync()
                    }

                    override fun onRelease() {
                        Log.d("FileSync", "SAF: Proxy released for $documentId, starting background upload")
                        thread {
                            try {
                                raf.close()
                                if (!tempFile.exists()) {
                                    Log.e("FileSync", "SAF: Temp file missing for $documentId")
                                    return@thread
                                }
                                Log.d("FileSync", "SAF: Temp file size for $documentId: ${tempFile.length()} bytes")
                                
                                tempFile.inputStream().use { input ->
                                    val updatedRemote = runBlocking {
                                        provider.write(path, input, overwrite = true)
                                    }
                                    Log.d("FileSync", "SAF: Upload finished for $documentId")
                                    runBlocking {
                                        metadataDao.updateEtagAndSynced(documentId, updatedRemote?.etag)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("FileSync", "SAF: Upload failed in onRelease for $documentId", e)
                            } finally {
                                if (tempFile.exists()) tempFile.delete()
                            }
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            thread {
                try {
                    val remoteStream = runBlocking { provider.read(path) }
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
                    remoteStream.use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                    out.close()
                    Log.d("FileSync", "SAF: Download finished for $documentId")
                } catch (e: Exception) {
                    Log.e("FileSync", "SAF: Download failed for $documentId", e)
                    try { writeEnd.closeWithError(e.message ?: "stream error") } catch (ex: Exception) {}
                } finally {
                    try { writeEnd.close() } catch (e: Exception) {}
                }
            }
            return readEnd
        }
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        Log.d("FileSync", "SAF: createDocument in $parentDocumentId name $displayName")
        if (parentDocumentId == null || displayName == null) throw FileNotFoundException()

        val providerId = parentDocumentId.substringBefore(":")
        val parentPath = parentDocumentId.substringAfter(":", "").trim('/')
        
        val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        val documentId = "$providerId:$newPath"

        val metadata = FileMetadata(
            id = documentId,
            parentId = parentDocumentId,
            providerId = providerId,
            path = newPath,
            name = displayName,
            mimeType = mimeType ?: "application/octet-stream",
            size = 0,
            modifiedAt = System.currentTimeMillis(),
            isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mimeType,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )

        runBlocking {
            if (metadata.isDirectory) {
                val provider = providerRegistry.getProvider(providerId)
                provider?.mkdir(newPath)
            }
            metadataDao.insert(metadata)
        }

        val notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        context?.contentResolver?.notifyChange(notifyUri, null)

        return documentId
    }

    override fun deleteDocument(documentId: String?) {
        Log.d("FileSync", "SAF: deleteDocument $documentId")
        if (documentId == null) return
        val providerId = documentId.substringBefore(":")
        val path = documentId.substringAfter(":", "").trim('/')
        
        runBlocking {
            val provider = providerRegistry.getProvider(providerId)
            provider?.delete(path)
            metadataDao.getById(documentId)?.let { metadataDao.delete(it) }
        }
        
        val parentId = documentId.substringBeforeLast("/", "$providerId:/")
        val notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId)
        context?.contentResolver?.notifyChange(notifyUri, null)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        Log.d("FileSync", "SAF: renameDocument $documentId to $displayName")
        if (documentId == null || displayName == null) throw FileNotFoundException()
        
        val providerId = documentId.substringBefore(":")
        val oldPath = documentId.substringAfter(":", "").trim('/')
        val parentPath = oldPath.substringBeforeLast("/", "")
        val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        val newDocumentId = "$providerId:$newPath"
        
        runBlocking {
            val provider = providerRegistry.getProvider(providerId)
            provider?.move(oldPath, newPath)
            
            metadataDao.getById(documentId)?.let { oldMetadata ->
                metadataDao.delete(oldMetadata)
                metadataDao.insert(oldMetadata.copy(
                    id = newDocumentId,
                    path = newPath,
                    name = displayName
                ))
            }
        }
        
        return newDocumentId
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        return documentId.startsWith(parentDocumentId) || 
               documentId.substringBefore(":") == parentDocumentId.substringBefore(":")
    }

    private fun addDocumentRow(cursor: MatrixCursor, metadata: FileMetadata) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, metadata.id)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, metadata.mimeType)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, metadata.name)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, metadata.modifiedAt)
        row.add(DocumentsContract.Document.COLUMN_SIZE, metadata.size)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
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

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
