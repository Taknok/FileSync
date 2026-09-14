package com.kunzisoft.filesync.provider.api

import java.io.InputStream

interface RemoteProvider {

    suspend fun list(path: String): List<RemoteFile>

    suspend fun stat(path: String): RemoteFile?

    suspend fun read(path: String): InputStream

    suspend fun read(path: String, rangeStart: Long, rangeEnd: Long? = null): InputStream

    suspend fun write(
        path: String,
        input: InputStream,
        overwrite: Boolean
    ): RemoteFile?

    suspend fun delete(path: String)

    suspend fun mkdir(path: String)

    suspend fun move(
        source: String,
        target: String
    )

    suspend fun authenticate()

    suspend fun refresh()
}
