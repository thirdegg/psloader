package com.thirdegg.psloader

import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File

class FileServer(
    private val serverDir: File,
    private val onNewConnection: (String) -> Unit,
    port:Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response? {
        val uri = if (session.uri == "/") {
            "/index.html"
        } else {
            session.uri
        }
        session.headers["remote-addr"]?.let {
            onNewConnection(it)
        }
        val file = File(serverDir, ".$uri")
        if (!file.exists() || file.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "plain/text", "not found")
        }
        return newChunkedResponse(Response.Status.OK, getMimeType(file), file.inputStream())
    }

    private fun getMimeType(file: File): String? {
        var type: String? = null
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return type
    }
}