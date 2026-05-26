package com.kunzisoft.filesync.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    fun getAllProviders(): Flow<List<ProviderConfig>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProviderById(id: String): ProviderConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(config: ProviderConfig)

    @Delete
    suspend fun deleteProvider(config: ProviderConfig)
}
