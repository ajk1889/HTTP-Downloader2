package resonance.http.httpdownloader.implementations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import resonance.http.httpdownloader.core.OutputObj
import resonance.http.httpdownloader.core.otherwise
import resonance.http.httpdownloader.core.silently
import resonance.http.httpdownloader.helpers.toUri
import resonance.http.httpdownloader.helpers.transferTo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class DocIO(private val context: Context, private val task: TransferWrapper) : OutputObj {
    private val doc: DocumentFile by lazy {
        val docBase: DocumentFile =
            DocumentFile.fromTreeUri(context, task.outputFolder.toUri())!!
        docBase.findFile(task.fileName)
            ?: docBase.createFile("*/*", task.fileName)
            ?: throw RuntimeException("Can't make file")
    }
    private val docChannel: FileChannel by lazy {
        with(context.contentResolver.openFileDescriptor(doc.uri, "rw")!!) {
            val docOPStream = java.io.FileOutputStream(fileDescriptor)
            val written = if (task.emptyFirstMode && task.transfer?.isCreatingEmptyFile != true) {
                task.transfer?.written ?: task.written
            } else doc.length()
            docOPStream.channel.position(written)
        }
    }

    override fun length(): Long = doc.length()
    override fun close() = silently { docChannel.close() }
    override fun reset() = { !doc.exists() || doc.delete() || doc.length() == 0L } otherwise false
    override fun getUri(): Uri = doc.uri

    override fun write(buffer: ByteArray, len: Int) {
        docChannel.write(ByteBuffer.wrap(buffer, 0, len))
    }

    override fun read(len: Int): ByteArray {
        val op = ByteArrayOutputStream()
        val ip = context.contentResolver.openInputStream(doc.uri)
        ip?.transferTo(op, len)
        return op.toByteArray()
    }

    companion object {
        val atATime: String get() = "YXQgYSB0aW1l"
    }
}