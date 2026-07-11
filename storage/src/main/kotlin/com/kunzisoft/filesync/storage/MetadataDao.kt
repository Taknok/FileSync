package com.kunzisoft.filesync.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {
    @Query("SELECT * FROM file_metadata WHERE id = :id")
    suspend fun getById(id: String): FileMetadata?

    @Query("SELECT * FROM file_metadata WHERE providerId = :providerId AND path = :path")
    fun observeByPath(providerId: String, path: String): Flow<FileMetadata?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: FileMetadata)

    @Delete
    suspend fun delete(metadata: FileMetadata)

    @Query("SELECT * FROM file_metadata WHERE providerId = :providerId")
    suspend fun getByProvider(providerId: String): List<FileMetadata>

    @Query("SELECT * FROM file_metadata WHERE parentId = :parentId")
    suspend fun getChildren(parentId: String): List<FileMetadata>

    @Query("SELECT * FROM file_metadata WHERE syncStatus = 'CONFLICT'")
    fun observeConflicts(): Flow<List<FileMetadata>>

    @Query("UPDATE file_metadata SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE file_metadata SET etag = :etag, syncStatus = 'SYNCED' WHERE id = :id")
    suspend fun updateEtagAndSynced(id: String, etag: String?)
}
