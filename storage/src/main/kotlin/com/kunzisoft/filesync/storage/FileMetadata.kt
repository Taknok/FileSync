package com.kunzisoft.filesync.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_metadata")
data class FileMetadata(
    @PrimaryKey val id: String, // providerId:path
    val parentId: String, // providerId:parentPath
    val providerId: String,
    val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
    val etag: String?,
    val localPath: String? = null,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val lastSyncTime: Long = 0L
)

enum class SyncStatus {
    SYNCED, PENDING, CONFLICT, ERROR
}
