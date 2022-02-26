package resonance.http.httpdownloader.implementations

import resonance.http.httpdownloader.core.DownloadObject
import resonance.http.httpdownloader.core.InputObj
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.core.silently
import resonance.http.httpdownloader.helpers.Pref
import java.io.IOException
import java.io.InputStream

open class InputObject(
    private val downloadObject: DownloadObject,
    private val stream: InputStream
) : InputObj {
    var lastRefreshed = now()

    override fun read(buffer: ByteArray, len: Int): Int {
        downloadObject.run {
            if (isStreamingServer &&
                response?.isContentLengthType == false &&
                now() - lastRefreshed > Pref.streamRefreshDelay
            ) {
                lastRefreshed = now()
                throw IOException("Speed low; refresh connection")
            }
        }
        return stream.read(buffer, 0, len)
    }

    override fun close() = silently { stream.close() }
}