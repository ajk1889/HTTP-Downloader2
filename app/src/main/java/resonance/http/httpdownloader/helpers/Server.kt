package resonance.http.httpdownloader.helpers

import android.os.Environment
import kotlinx.coroutines.*
import resonance.http.httpdownloader.core.silently
import resonance.http.httpdownloader.core.str
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.math.min

class Server(private val port: Int = 1234) {
    companion object {
        var sleep: Long = 0L
    }

    var loggingAllowed = true
    var contentLengthMode = false
    var noLengthMode = false
    var path123: String = "/100mb.txt"
        set(value) {
            field = if (value.first() != '/') "/$value" else value
        }
    var htdocs: File? = Environment.getExternalStorageDirectory()
    var cookies: String? = null
    var size123: Long = 100 * 1024 * 1024
    var ping: Long = 0L
    var bufferSize: Int = 8 * 1024
    var onTextShared: (String?) -> Unit = ::println

    private lateinit var server: ServerSocket
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val clients = mutableSetOf<Socket>()

    var isRunning = false
    fun start() {
        if (isRunning) throw RuntimeException("Server already running")
        isRunning = true
        server = ServerSocket(port)

        ioScope.launch {
            silently { while (isRunning) serve(nextConnection()) }
        }
    }

    fun stop() {
        if (!isRunning) throw RuntimeException("Server not running")
        isRunning = false
        server.close()
        for (socket in clients) silently { socket.close() }
    }

    private suspend fun nextConnection(): Socket = withContext(Dispatchers.IO) { server.accept() }

    private fun serve(client: Socket) {
        clients.add(client)
        ioScope.launch {
            if (loggingAllowed) println("New connection from $client")
            client.soTimeout = 1000
            val inputData = client.readInput()
            val request = inputData.extractHeaders()
            val response = generateResponse(request, inputData, client)
            if (ping > 0) delay(ping)
            client.sendResponse(response)
            client.closeConnection()
            if (loggingAllowed) println("IO completed for $client")
        }
    }

    private fun Map<String, String>.getContentRange(): Pair<Long, Long>? {
        val range = get("Range") ?: return null
        if (!range.startsWith("bytes=")) return null
        var start = 0L
        silently { start = range.substring(6, range.indexOf('-')).toLong() }
        var end = -1L
        silently { end = range.substring(range.indexOf('-') + 1).toLong() }
        return start to end
    }

    private fun areCookiesValid(headers: Map<String, String>): Boolean {
        val requestCookies = headers["Cookie"]
        val acceptableCookies = cookies
        return acceptableCookies == null
                || requestCookies != null && requestCookies.contains(acceptableCookies)
    }

    private suspend fun generateResponse(
        headers: Map<String, String>,
        alreadyRead: String,
        client: Socket
    ): InputStream = withContext(Dispatchers.Default) {
        if (headers.isEmpty())
            return@withContext ByteArrayInputStream(ByteArray(0))
        if (!areCookiesValid(headers))
            return@withContext toInputStream("<h2>Invalid cookies</h2>", 403)

        when (val path = headers["path"]) {
            null -> return@withContext toInputStream("<h2>No file specified</h2>", 500)
            "/txt", "/txt/" -> {
                if (headers.containsKey("Content-Length") && headers["Content-Type"]?.contains("multipart/form-data") != true)
                    withContext(Dispatchers.Main) { onTextShared(client.readData(alreadyRead)["text"]) }
                return@withContext toInputStream(
                    """<form method=post>
                       |    <textarea name="text" style="width: 100%; height: 90%"></textarea>
                       |    <br><br>
                       |    <input type="submit">
                       |<form/>""".trimMargin(),
                    200
                )
            }
            path123 -> {
                if (loggingAllowed)
                    println("Requested 123 file; size = ${size123.formatted()}")
                return@withContext toInputStream(
                    Generator(size123),
                    headers.getContentRange()
                )
            }
            else -> htdocs?.also {
                if (it.exists() && it.isFile) {
                    if (loggingAllowed)
                        println("Requested file: " + it.absolutePath + " length=" + it.length().formatted())
                    return@withContext toInputStream(it, headers.getContentRange())
                } else {
                    val file = File(htdocs, URLDecoder.decode(path, "utf8"))
                    if (loggingAllowed)
                        println("Requested file: " + file.absolutePath + " length=" + file.length().formatted())
                    if (file.exists()) return@withContext toInputStream(
                        file,
                        headers.getContentRange()
                    )
                }
            }
        }
        return@withContext toInputStream("<h2>Can't access the file</h2>", 404)
    }

    private suspend fun String.extractHeaders(): Map<String, String> =
        withContext(Dispatchers.Default) {
            val headers = mutableMapOf<String, String>()
            if (isNullOrBlank()) return@withContext headers

            val items = split("\r\n")
            headers["path"] =
                items[0].substring(items[0].indexOf(" ") + 1, items[0].lastIndexOf(" "))
            for (i in 1 until items.size) {
                val separatorIndex = items[i].indexOf(": ")
                if (separatorIndex < 0) continue
                val key = items[i].substring(0, separatorIndex)
                val value = items[i].substring(separatorIndex + 2)
                if (key in headers) headers[key] += "; $value"
                else headers[key] = value
            }
            if (contentLengthMode) headers.remove("Range")
            headers
        }

    private suspend fun Socket.closeConnection() = withContext(Dispatchers.IO) {
        close()
        clients.remove(this@closeConnection)
    }

    private suspend fun Socket.readInput(
        alreadyRead: String = "",
        retryCount: Int = 2
    ): String = withContext(Dispatchers.IO) {
        try {
            val ip = getInputStream()
            val data = StringBuilder(alreadyRead)
            val bfr = ByteArray(1024)
            try {
                var n = ip.read(bfr)
                while (n > 0) {
                    data.append(String(bfr, 0, n))
                    if (data.contains("\r\n\r\n")) {
                        data.removeRange(data.indexOf("\r\n\r\n"), data.length)
                        break
                    }
                    n = ip.read(bfr)
                }
            } catch (e: IOException) {
                if (retryCount > 0) {
                    if (loggingAllowed) println("$e Retrying read")
                    return@withContext readInput(data.str, retryCount - 1)
                }
            }
            data.str
        } catch (e: IOException) {
            ""
        }
    }

    private suspend fun Socket.readData(alreadyRead: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val inputStream = getInputStream()
            val builder = StringBuilder(alreadyRead)
            inputStream.readCompleted(builder)
            with(builder.substring(builder.indexOf("\r\n\r\n") + 4)) {
                val keyValue = split("=")
                mutableMapOf<String, String>().also {
                    for (i in keyValue.indices step 2)
                        it[keyValue[i]] = URLDecoder.decode(keyValue[i + 1], "utf8")
                }
            }
        }

    /**
     * Returns whether the data from a socket is completely read.
     * If content-length is specified in HTTP-Headers, this method continue to read until end of length & returns true.
     * If Content-Length is not specified, this method returns true if the HTTP-Headers are fully read
     */
    private suspend fun InputStream.readCompleted(alreadyRead: StringBuilder): Boolean =
        withContext(Dispatchers.IO) {
            val endIndex = alreadyRead.indexOf("\r\n\r\n")
            if (endIndex == -1) return@withContext false
            val contentLength = alreadyRead.split("Content-Length: ").let {
                if (it.size == 1) return@withContext true
                it[1].substring(0, it[1].indexOf('\r')).toLong()
            }
            val bytesRemaining = contentLength - (alreadyRead.length - (endIndex + 4))
            if (bytesRemaining == 0L) return@withContext true

            val bfr = ByteArray(1024)
            var n = read(bfr)
            var bytesRead = n.toLong()
            while (n > 0) {
                alreadyRead.append(String(bfr, 0, n))
                if (bytesRead == bytesRemaining) break
                n = read(bfr)
                bytesRead += n
            }
            true
        }

    private suspend fun Socket.sendResponse(response: InputStream) = withContext(Dispatchers.IO) {
        try {
            this@sendResponse.use { response.copyTo(it.getOutputStream(), bufferSize) }
        } catch (e: IOException) {
            if (loggingAllowed) println(e)
            ""
        }
    }

    private fun toInputStream(content: String, responseCode: Int): InputStream {
        val builder = StringBuilder("HTTP/1.1 $responseCode ${errorCodes[responseCode]}\r\n")
        builder.append("Content-Type: text/html\r\n")
        builder.append("Connection: keep-alive\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        val extra = testFileLink
        builder.append("Content-Length: ${content.length + extra.length}\r\n")
        cookies?.also { builder.append("Set-Cookie: $it\r\n") }
        builder.append("\r\n")
        builder.append(content).append(extra)
        return builder.str.byteInputStream()
    }

    private suspend fun toInputStream(
        file: File,
        contentRange: Pair<Long, Long>? = null
    ): InputStream = withContext(Dispatchers.IO) {
        if (file.isDirectory) return@withContext folderListStream(file)
        try {
            var inputStream: InputStream = FileInputStream(file)
            val builder = StringBuilder("HTTP/1.1 200 OK\r\n")
            builder.append("Server: TestSuit\r\n")
            when {
                file.name.endsWithAny(".html", ".htm") ->
                    builder.append("Content-Type: text/html\r\n")
                file.name.endsWithAny(
                    ".java", ".txt",
                    ".kt", ".py", ".php", ".json", ".js", ".css", ".sh", ".bat",
                    ".pyw", ".md", ".ini", ".cfg"
                ) -> builder.append("Content-Type: text/plain\r\n")
                else -> {
                    builder.append("Content-Type: application/octet-stream\r\n")
                    builder.append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
                }
            }
            builder.append("Connection: keep-alive\r\n")
            builder.append("Accept-Ranges: bytes\r\n")
            cookies?.also { builder.append("Set-Cookie: $it\r\n") }

            if (contentRange != null && !noLengthMode && !contentLengthMode) {
                val (offset, limit) = contentRange
                inputStream.skip(offset)
                if (limit > 0) {
                    inputStream = inputStream.toLimitedStream(limit + 1)
                    builder.append("Content-Length: ${limit - offset + 1}\r\n")
                    builder.append("Content-Range: bytes $offset-$limit/${file.length()}\r\n")
                } else {
                    val len = file.length()
                    builder.append("Content-Length: ${len - offset}\r\n")
                    builder.append("Content-Range: bytes $offset-${len - 1}/$len\r\n")
                }
            } else if (!noLengthMode)
                builder.append("Content-Length: ${file.length()}\r\n")

            builder.append("\r\n")
            return@withContext builder.str.byteInputStream() + inputStream
        } catch (e: FileNotFoundException) {
            return@withContext toInputStream("<h2>File not found exception</h2>", 404)
        }
    }

    private suspend fun folderListStream(file: File): InputStream = withContext(Dispatchers.IO) {
        val files = file.listFiles()
            ?: return@withContext toInputStream("<h2>Folder not accessible</h2>", 500)
        if (files.isEmpty())
            return@withContext toInputStream("<h2>Empty folder</h2>", 200)
        files.sortWith { f1, f2 ->
            if (f1.isFile && f2.isFile || f1.isDirectory && f2.isDirectory)
                f1.name.compareTo(f2.name)
            else if (f1.isDirectory) -1
            else 1
        }
        val builder = StringBuilder()
        val subLen = htdocs?.canonicalPath?.length ?: 0
        for (f in files) {
            var relativePath = f.canonicalPath.substring(subLen)
            if (!relativePath.startsWith('/')) relativePath = "/$relativePath"
            builder.append("<a href='")
                .append(relativePath)
                .append("'>")
            if (f.isDirectory) builder.append("<font color='red' size=4>")
            else builder.append("<font color='black'>")
            builder.append(f.name).append("</font></a><br/>")
        }
        return@withContext toInputStream(
            """
            |<html>
            |<title>${file.name}</title>
            |<body>$builder</body>
            |</html>
            """.trimMargin(), 200
        )
    }

    private fun toInputStream(
        stream: Generator,
        contentRange: Pair<Long, Long>? = null
    ): InputStream {
        val builder = StringBuilder("HTTP/1.1 200 OK\r\n")
        builder.append("Server: TestSuit\r\n")
        builder.append("Content-Type: application/octet-stream\r\n")
        builder.append("Connection: keep-alive\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        builder.append("Content-Disposition: attachment; filename=\"${File(path123).name}\"\r\n")

        cookies?.also { builder.append("Set-Cookie: $it\r\n") }

        if (contentRange != null) {
            var (offset, limit) = contentRange
            stream.offset = offset
            if (limit < 0L) limit = stream.limit - 1
            stream.limit = min(limit + 1, size123)
            builder.append("Content-Range: bytes ${stream.offset}-${stream.limit - 1}/$size123\r\n")
        }
        if (!noLengthMode)
            builder.append("Content-Length: ${stream.length}\r\n")
        builder.append("\r\n")
        return builder.str.byteInputStream() + stream
    }

    private val errorCodes =
        mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 500 to "Internal Server Error")

    private val testFileLink: String
        get() = "\n\n<br/><br/><a href='$path123'>Download a test file (${size123.formatted()})</a>"

    private fun String.endsWithAny(vararg suffixes: String): Boolean {
        for (s in suffixes) if (this.endsWith(s)) return true
        return false
    }
}