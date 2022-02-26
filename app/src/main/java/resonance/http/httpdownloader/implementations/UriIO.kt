package resonance.http.httpdownloader.implementations

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import resonance.http.httpdownloader.core.OutputObj
import resonance.http.httpdownloader.core.otherwise
import resonance.http.httpdownloader.core.silently
import resonance.http.httpdownloader.helpers.toUriFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class UriIO(private val context: Context, private val task: TransferWrapper) : OutputObj {
    private val uriFile: UriFile by lazy {
        task.outputFolder.toUriFile(context)
    }
    private val uriFileChannel: FileChannel by lazy {
        with(uriFile.fileChannel) {
            val written =
                if (task.emptyFirstMode && task.transfer?.isCreatingEmptyFile != true) {
                    task.transfer?.written ?: task.written
                } else uriFile.size
            this.position(written)
        }
    }

    override fun read(len: Int): ByteArray = uriFile.readNBytes(len)
    override fun length(): Long = uriFile.getLatestSize()
    override fun close() = silently { uriFile.fileChannel.close() }

    override fun reset() = {
        DocumentsContract.deleteDocument(context.contentResolver, uriFile.uri)
    } otherwise false

    override fun getUri(): Uri = uriFile.uri

    override fun write(buffer: ByteArray, len: Int) {
        uriFileChannel.write(ByteBuffer.wrap(buffer, 0, len))
    }
}