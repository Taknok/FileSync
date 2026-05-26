package com.kunzisoft.filesync

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kunzisoft.filesync.storage.FileMetadata
import com.kunzisoft.filesync.storage.MetadataDao
import com.kunzisoft.filesync.storage.ProviderDao
import com.kunzisoft.filesync.sync.SyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

class FileSyncDocumentProvider : DocumentsProvider() {

    private lateinit var metadataDao: MetadataDao
    private lateinit var providerDao: ProviderDao
    private lateinit var workManager: WorkManager

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            ProviderEntryPoint::class.java
        )
        metadataDao = entryPoint.metadataDao()
        providerDao = entryPoint.providerDao()
        workManager = entryPoint.workManager()
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

        // Set notification URI so SAF re-queries when database changes
        val notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        cursor.setNotificationUri(context?.contentResolver, notifyUri)

        // Throttled background sync
        val providerId = parentDocumentId.substringBefore(":")
        val path = parentDocumentId.substringAfter(":", "/")
        val config = runBlocking { providerDao.getProviderById(providerId) }
        
        if (config != null) {
            val now = System.currentTimeMillis()
            // Only sync if last sync was more than 30 seconds ago
            if (now - config.lastSyncTime > 30000) {
                Log.d("FileSync", "SAF: Triggering background sync for $providerId path $path")
                val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(androidx.work.workDataOf(
                        "providerId" to providerId,
                        "path" to path
                    ))
                    .build()
                workManager.enqueueUniqueWork("sync_${providerId}_${path.hashCode()}", ExistingWorkPolicy.KEEP, syncRequest)
            } else {
                Log.d("FileSync", "SAF: Skipping sync (last sync was ${now - config.lastSyncTime}ms ago)")
            }
        }
        
        val children = runBlocking { metadataDao.getChildren(parentDocumentId) }
        Log.d("FileSync", "SAF: Found ${children.size} children in DB")
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
        throw FileNotFoundException()
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
