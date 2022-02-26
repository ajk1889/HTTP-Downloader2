package resonance.http.httpdownloader.helpers

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.implementations.UriFile
import java.io.File

class MediaScanner(private val context: Context) {
    private fun scan(path: String) {
        var conn: MediaScannerConnection? = null
        val mediaClient = object : MediaScannerConnection.MediaScannerConnectionClient {
            override fun onMediaScannerConnected() {
                conn?.scanFile(path, null)
                conn?.disconnect()
            }

            override fun onScanCompleted(path: String?, uri: Uri?) {
                log("MediaScanner", "Scan completed $path -> $uri")
            }
        }
        conn = MediaScannerConnection(context, mediaClient)
        conn.connect()
    }

    fun scan(name: String, op: Uri) {
        val path = op.path
        if (path != null) scan(path)
    }

    fun scan(name: String, file: File) = scan(File(file, name).absolutePath)
    fun scan(uriFile: UriFile) {
        val path = uriFile.uri.path
        if (path != null) scan(path)
    }
}