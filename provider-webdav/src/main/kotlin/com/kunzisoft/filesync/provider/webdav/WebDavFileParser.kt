package com.kunzisoft.filesync.provider.webdav

import android.util.Xml
import com.kunzisoft.filesync.provider.api.RemoteFile
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

class WebDavFileParser {
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    fun parse(inputStream: InputStream, baseUrl: String): List<RemoteFile> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(inputStream, null)

        // Parse baseUrl to get path for relative href calculation
        val baseUriPath = try { URL(baseUrl).path.trimEnd('/') } catch (e: Exception) { "" }

        val files = mutableListOf<RemoteFile>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "response") {
                parseResponse(parser, baseUriPath)?.let { files.add(it) }
            }
            eventType = parser.next()
        }
        return files
    }

    private fun parseResponse(parser: XmlPullParser, baseUriPath: String): RemoteFile? {
        var href = ""
        var name = ""
        var isDirectory = false
        var size = 0L
        var modifiedAt = 0L
        var etag = ""

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "response")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "href" -> href = URLDecoder.decode(parser.nextText(), "UTF-8")
                    "displayname" -> name = parser.nextText()
                    "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0L
                    "getlastmodified" -> modifiedAt = parseDate(parser.nextText())
                    "getetag" -> etag = parser.nextText().trim('"')
                    "resourcetype" -> {
                        if (parseIsDirectory(parser)) isDirectory = true
                    }
                }
            }
            parser.next()
        }

        if (href.isBlank()) return null
        
        // Normalize path: extract only the part relative to the WebDAV root
        // Href might be "/dav/path/" or "http://host/dav/path/"
        val fullPath = if (href.startsWith("http")) URL(href).path else href
        val path = fullPath.removePrefix(baseUriPath).trim('/')

        if (name.isBlank()) name = path.substringAfterLast("/", path)

        return RemoteFile(
            id = path,
            path = path,
            name = name,
            mimeType = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
            size = size,
            modifiedAt = modifiedAt,
            isDirectory = isDirectory,
            etag = etag
        )
    }

    private fun parseIsDirectory(parser: XmlPullParser): Boolean {
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "resourcetype")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "collection") return true
            parser.next()
        }
        return false
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
