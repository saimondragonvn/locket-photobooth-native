package com.elingo.locket.core.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile

/**
 * Zero-copy streaming RequestBody supporting both full files and 1 MB byte-range chunks
 * without allocating large ByteArrays on the JVM heap.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentTypeString: String,
    private val offset: Long = 0L,
    private val length: Long = (file.length() - offset).coerceAtLeast(0L),
    private val onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = contentTypeString.toMediaTypeOrNull()

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        var uploaded = 0L
        var lastEmitAt = 0L

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            while (remaining > 0L) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break

                sink.write(buffer, 0, read)
                remaining -= read
                uploaded += read

                val now = System.currentTimeMillis()
                if (onProgress != null && (now - lastEmitAt >= 60L || remaining == 0L)) {
                    lastEmitAt = now
                    onProgress.invoke(uploaded, length)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 16 * 1024
    }
}

