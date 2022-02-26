package resonance.http.httpdownloader.implementations

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import resonance.http.httpdownloader.core.KB
import resonance.http.httpdownloader.core.get
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.nameToComparableLong
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel

class UriFile(private val context: Context, uriStr: String) {
    companion object {
        const val TAG = "UriFile"
    }

    val uri = Uri.parse(uriStr) ?: throw RuntimeException("Invalid uri: $uriStr")
    var size: Long = 0
    lateinit var name: String

    @SuppressLint("Recycle")
    fun getLatestSize(): Long {
        context.contentResolver.query(uri, null, null, null, null, null).also {
            if (it == null) return@also
            if (it.moveToFirst()) {
                val sizeIndex: Int = it.getColumnIndex(OpenableColumns.SIZE)
                size = if (!it.isNull(sizeIndex)) it.getString(sizeIndex).toLong() else 0
                it.close()
            }
        }
        return size
    }

    val fileChannel: FileChannel by lazy {
        val descr = context.contentResolver.openFileDescriptor(uri, "rw")
        if (descr != null) {
            FileOutputStream(descr.fileDescriptor).channel
        } else throw RuntimeException("Can't open file descriptor")
    }

    init {
        initialize()
    }

    @SuppressLint("Recycle")
    private fun initialize() {
        context.contentResolver.query(uri, null, null, null, null, null).also {
            if (it == null) {
                name = "unknown"
                return@also
            }
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))

                val sizeIndex: Int = it.getColumnIndex(OpenableColumns.SIZE)
                size = if (!it.isNull(sizeIndex)) it.getString(sizeIndex).toLong() else 0
                it.close()
            }
        }
    }

    fun reInitialize() = initialize()

    fun getInputStream(): InputStream {
        return context.contentResolver.openInputStream(uri)!!
    }

    fun getOutputStream(append: Boolean): OutputStream {
        val mode = if (append) "wa" else "w"
        return context.contentResolver.openOutputStream(uri, mode)!!
    }

    /**
     * reads first N bytes of this file
     * @param N = max amount of bytes to be read
     * @return first N bytes of the file if file size >= `size`
     * @return full file as byteArray, if file size < 50KB
     * @return blank byteArray in case of error
     */
    fun readNBytes(N: Int = 50.KB): ByteArray {
        return try {
            val ip = context.contentResolver.openInputStream(uri)
                ?: return ByteArray(0).also { log(TAG, "Null inputStream for uri: $uri") }
            val bfr = ByteArray(N)
            var lastRead = ip.read(bfr)
            if (lastRead == -1) return ByteArray(0)
            var total = 0
            while (total < N) {
                total += lastRead
                lastRead = ip.read(bfr, total, N - total)
                if (lastRead == -1) break
            }
            bfr[0 to total].also { log(TAG, "Read ${it.size} bytes") }
        } catch (e: Exception) {
            log(TAG, "Exception: ", e)
            ByteArray(0)
        }
    }

    override fun toString(): String {
        return "UriFile: $name; size=$size"
    }

    fun getComparable() = nameToComparableLong(name)
}