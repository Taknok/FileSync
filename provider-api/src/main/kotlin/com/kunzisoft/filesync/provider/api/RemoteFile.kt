package com.kunzisoft.filesync.provider.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class RemoteFile(
    val id: String,
    val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
    val etag: String? = null
) : Parcelable
