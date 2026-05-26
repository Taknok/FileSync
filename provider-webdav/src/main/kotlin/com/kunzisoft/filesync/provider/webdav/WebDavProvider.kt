package com.kunzisoft.filesync.provider.webdav

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.kunzisoft.filesync.provider.api.RemoteFile
import com.kunzisoft.filesync.provider.api.RemoteProvider
import java.io.InputStream
import javax.inject.Inject

class WebDavProvider(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val user: String?,
    private val pass: String?
) : RemoteProvider {

    private val parser = WebDavFileParser()

    private fun addAuth(request: Request.Builder): Request.Builder {
        if (user != null && pass != null) {
            val credential = okhttp3.Credentials.basic(user, pass)
            request.header("Authorization", credential)
        }
        return request
    }

    override suspend fun list(path: String): List<RemoteFile> {
        val request = Request.Builder()
            .url(buildUrl(path))
            .method("PROPFIND", null)
            .header("Depth", "1")
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to list: ${response.code}")
        
        return response.body?.byteStream()?.use { 
            parser.parse(it, baseUrl) 
        } ?: emptyList()
    }

    override suspend fun stat(path: String): RemoteFile? {
        val request = Request.Builder()
            .url(buildUrl(path))
            .method("PROPFIND", null)
            .header("Depth", "0")
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) return null
        
        return response.body?.byteStream()?.use { 
            parser.parse(it, baseUrl).firstOrNull() 
        }
    }

    override suspend fun read(path: String): InputStream {
        val request = Request.Builder()
            .url(buildUrl(path))
            .get()
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to read file: ${response.code}")
        return response.body?.byteStream() ?: throw Exception("Empty body")
    }

    override suspend fun write(path: String, input: InputStream, overwrite: Boolean) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .put(input.readBytes().toRequestBody())
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to write file: ${response.code}")
    }

    override suspend fun delete(path: String) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .delete()
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to delete: ${response.code}")
    }

    override suspend fun mkdir(path: String) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .method("MKCOL", null)
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to mkdir: ${response.code}")
    }

    override suspend fun move(source: String, target: String) {
        val request = Request.Builder()
            .url(buildUrl(source))
            .header("Destination", buildUrl(target))
            .method("MOVE", null)
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to move: ${response.code}")
    }

    override suspend fun authenticate() {
    }

    override suspend fun refresh() {
    }

    private fun buildUrl(path: String): String {
        return "$baseUrl/${path.trimStart('/')}"
    }
}
