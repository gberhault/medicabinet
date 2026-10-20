// Fixture: handing files to other apps or writing documents outside .../features/transfer/ triggers
// sharing_confined_to_transfer.
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class Shares(private val context: Context) {
    fun share(file: File): Uri = FileProvider.getUriForFile(context, "authority", file)

    fun write(uri: Uri) = context.contentResolver.openOutputStream(uri)?.use { it.write(1) }
}
