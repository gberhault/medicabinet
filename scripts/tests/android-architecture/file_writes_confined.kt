// Fixture: writing a file outside .../features/transfer/ and .../persistence/ triggers file_writes_confined.
import java.io.File

class Writes {
    fun save(file: File, bytes: ByteArray) = file.writeBytes(bytes)
}
