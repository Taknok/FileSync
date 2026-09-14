package com.kunzisoft.filesync.provider.webdav

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import com.kunzisoft.filesync.provider.api.RemoteFile
import com.kunzisoft.filesync.provider.api.RemoteProvider
import java.io.InputStream
import android.util.Log

class WebDavProvider(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val user: String?,
    private val pass: String?
) : RemoteProvider {

    private val parser = WebDavFileParser()

    private fun addAuth(request: Request.Builder): Request.Builder {
        if (user != null && pass != null) {
            val credential = Credentials.basic(user, pass)
            request.header("Authorization", credential)
        }
        return request
    }

    override suspend fun list(path: String): List<RemoteFile> {
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: PROPFIND $url")
        val request = Request.Builder()
            .url(url)
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
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: PROPFIND (Depth 0) $url")
        val request = Request.Builder()
            .url(url)
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
        return read(path, 0, null)
    }

    override suspend fun read(path: String, rangeStart: Long, rangeEnd: Long?): InputStream {
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: GET $url (Range: $rangeStart-${rangeEnd ?: ""})")
        
        val request = Request.Builder()
            .url(url)
            .get()
        
        if (rangeStart > 0 || rangeEnd != null) {
            val rangeHeader = "bytes=$rangeStart-${rangeEnd ?: ""}"
            request.header("Range", rangeHeader)
        }
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw Exception("Failed to read file: ${response.code}")
        }
        return response.body?.byteStream() ?: throw Exception("Empty body")
    }

    override suspend fun write(path: String, input: InputStream, overwrite: Boolean): RemoteFile? {
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: Starting PUT request to $url")
        
        // Use BufferedInputStream to peek if it's empty, as input.available() is unreliable for PFD streams
        val bis = java.io.BufferedInputStream(input)
        bis.mark(1)
        val firstByte = bis.read()
        val isEmpty = firstByte == -1
        bis.reset()

        // We need the length for some WebDAV servers to avoid 500 errors
        val contentLength = try { bis.available().toLong() } catch (e: Exception) { -1L }

        val requestBody = if (isEmpty) {
            Log.d("FileSync", "WebDAV: File is empty, using 0-length request body")
            "".toRequestBody("application/octet-stream".toMediaType())
        } else {
            object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = contentLength
                override fun writeTo(sink: BufferedSink) {
                    Log.d("FileSync", "WebDAV: Beginning stream pump to server (length: $contentLength)")
                    bis.source().use { source ->
                        sink.writeAll(source)
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .header("X-File-Name", path.substringAfterLast('/'))
            .header("X-Requested-With", "FileSync")
        
        addAuth(request)
        
        val call = client.newCall(request.build())
        Log.d("FileSync", "WebDAV: Executing call for $url")
        
        val response = call.execute()
        if (!response.isSuccessful) {
            val errorBody = try { response.body?.string() } catch (e: Exception) { "Could not read error body" }
            Log.e("FileSync", "WebDAV: PUT failed with HTTP ${response.code}")
            Log.e("FileSync", "WebDAV: Server Error Body: $errorBody")
            throw Exception("Failed to write file: ${response.code}")
        } else {
            Log.d("FileSync", "WebDAV: PUT successful for $url")
            // Try to get ETag from header or stat if not present
            val etag = response.header("ETag")
            return if (etag != null) {
                // We don't have all info (size, modifiedAt) from PUT response usually, so stat is safer
                stat(path)
            } else {
                stat(path)
            }
        }
    }

    override suspend fun delete(path: String) {
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: DELETE $url")
        val request = Request.Builder()
            .url(url)
            .delete()
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to delete: ${response.code}")
    }

    override suspend fun mkdir(path: String) {
        val url = buildUrl(path)
        Log.d("FileSync", "WebDAV: MKCOL $url")
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
        
        addAuth(request)
        
        val response = client.newCall(request.build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to mkdir: ${response.code}")
    }

    override suspend fun move(source: String, target: String) {
        val sourceUrl = buildUrl(source)
        val targetUrl = buildUrl(target)
        Log.d("FileSync", "WebDAV: MOVE $sourceUrl to $targetUrl")
        val request = Request.Builder()
            .url(sourceUrl)
            .header("Destination", targetUrl)
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
        val normalizedPath = path.trim('/')
        val base = baseUrl.trimEnd('/')
        return if (normalizedPath.isEmpty()) {
            "$base/"
        } else {
            val httpUrl = base.toHttpUrl().newBuilder()
            normalizedPath.split('/').forEach {
                if (it.isNotEmpty()) httpUrl.addPathSegment(it)
            }
            httpUrl.build().toString()
        }
    }
}
