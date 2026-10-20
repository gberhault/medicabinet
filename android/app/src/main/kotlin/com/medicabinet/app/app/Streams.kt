package com.medicabinet.app.app

import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val CHUNK_BYTES = 64 * 1024

/**
 * Reads up to [limit] bytes, or until the end of the stream, whichever comes first (`readNBytes` needs API 33). A
 * caller that must refuse files over a size reads `max + 1` bytes: getting more than `max` means the file is too
 * large, and nothing beyond that is ever loaded.
 */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    var remaining = limit
    while (remaining > 0) {
        val read = read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) break
        output.write(chunk, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}
