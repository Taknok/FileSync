package com.kunzisoft.filesync.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderConfig(
    @PrimaryKey val id: String, // UUID
    val type: String, // "webdav", "gdrive", etc.
    val name: String,
    val baseUrl: String? = null,
    val username: String? = null,
    val isEnabled: Boolean = true,
    val lastSyncTime: Long = 0L
)
