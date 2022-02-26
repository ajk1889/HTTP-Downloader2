package resonance.http.httpdownloader.implementations

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import resonance.http.httpdownloader.core.OutputObj
import resonance.http.httpdownloader.core.otherwise
import resonance.http.httpdownloader.core.silently
import resonance.http.httpdownloader.helpers.toFolder
import resonance.http.httpdownloader.helpers.transferTo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

class FileIO(private val context: Context, private val task: TransferWrapper) : OutputObj {
    private val file: File by lazy {
        File(task.outputFolder.toFolder(), task.fileName)
    }
    private val fileStream: RandomAccessFile by lazy {
        RandomAccessFile(file, "rw").apply {
            //When error occurred while creating an empty file, the writing should resume from end of file
            val written =
                if (task.emptyFirstMode && task.transfer?.isCreatingEmptyFile != true) {
                    task.transfer?.written ?: task.written
                } else file.length()
            seek(written)
        }
    }

    override fun write(buffer: ByteArray, len: Int) = fileStream.write(buffer, 0, len)
    override fun length(): Long = if (file.isFile) file.length() else 0L
    override fun close() = silently { fileStream.close() }
    override fun reset() = {
        !file.exists() || file.delete() || file.length() == 0L
    } otherwise false

    override fun getUri(): Uri = FileProvider.getUriForFile(
        context,
        context.applicationContext.packageName + ".provider",
        file
    )

    override fun read(len: Int): ByteArray {
        val op = ByteArrayOutputStream()
        val ip = FileInputStream(file)
        ip.transferTo(op, len)
        return op.toByteArray()
    }
}

class GenericFileProvider : FileProvider()