package com.medicabinet.app.persistence.reference

import com.medicabinet.core.reference.ReferenceUnavailableReason
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * Android cannot open SQLite inside the APK, so the bundled database is copied once into [directory] (internal,
 * excluded from backups: it is rebuilt from the APK). The copy is atomic, checked against the manifest's content hash,
 * and redone only when the bundled version changes (requirement L8).
 */
class ReferenceInstaller(private val assets: ReferenceAssets, private val directory: File) {
    private val target = File(directory, ReferenceAssets.DATABASE_NAME)
    private val marker = File(directory, INSTALLED_MARKER)

    /**
     * The installed database file, copying it from the assets when it is not there yet, when the bundled version
     * changed, or when [force] says the installed copy is not trustworthy. Call off the main thread.
     */
    @Throws(ReferenceUnavailableException::class)
    fun install(force: Boolean = false): File {
        val expected = expectedContentHash()
        if (!force && target.isFile && readMarker() == expected) return target
        directory.mkdirs()
        val temporary = File(directory, "${ReferenceAssets.DATABASE_NAME}$TEMPORARY_SUFFIX")
        try {
            val streamed = copyAssetTo(temporary)
            if (sha256Of(temporary) != streamed) {
                throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE)
            }
            verifyContent(temporary, expected)
            moveAtomically(temporary, target)
            writeMarker(expected)
        } catch (error: IOException) {
            throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE, error)
        } finally {
            temporary.delete()
        }
        return target
    }

    private fun expectedContentHash(): String {
        val text = assets.manifest() ?: throw ReferenceUnavailableException(ReferenceUnavailableReason.MISSING)
        return try {
            JSONObject(text).getString(CONTENT_HASH_KEY)
        } catch (error: JSONException) {
            throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE, error)
        }
    }

    /** Streams the asset into [file] and returns the SHA-256 of what was read. */
    private fun copyAssetTo(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = assets.database() ?: throw ReferenceUnavailableException(ReferenceUnavailableReason.MISSING)
        input.use { source ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** The copy must be a usable database whose content is exactly the one the manifest names. */
    private fun verifyContent(file: File, expected: String) {
        ReferenceDatabase.open(file).use { database ->
            if (database.contentSha256() != expected) {
                throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE)
            }
        }
    }

    private fun moveAtomically(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readMarker(): String? = if (marker.isFile) marker.readText().trim() else null

    /** Written last: an interrupted install leaves no marker, so the next call copies again. */
    private fun writeMarker(contentHash: String) {
        val temporary = File(directory, "$INSTALLED_MARKER$TEMPORARY_SUFFIX")
        temporary.writeText(contentHash)
        moveAtomically(temporary, marker)
    }

    private companion object {
        const val INSTALLED_MARKER = "installed.sha256"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val CONTENT_HASH_KEY = "contentSha256"
        const val BUFFER_SIZE = 64 * 1024
    }
}
