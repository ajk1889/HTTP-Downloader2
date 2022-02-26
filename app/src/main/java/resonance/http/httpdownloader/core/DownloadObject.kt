package resonance.http.httpdownloader.core

import android.webkit.CookieManager
import org.json.JSONObject
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.asSanitizedFileName
import resonance.http.httpdownloader.implementations.InputObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class DownloadObject(
    private val data: JSONObject,
    id: Long = data.getLong(C.dt.id)
) : Transfer(id) {
    //throws JSONException if data doesn't contain key: url & id

    companion object {
        const val TYPE = "DownloadObject"
    }

    private var url: String = data.getString(C.dt.url)
    private var headers: String? = null
    var isStreamingServer = data.optBoolean(C.dt.isStreamServer, false)

    lateinit var toOutputObject: (String?) -> OutputObj

    init {
        headers = data.optString(C.dt.headers, null)
        offset = data.optLong(C.dt.offset, 0)
        limit = data.optLong(C.dt.limit, -1)
        shouldCreateEmptyFile = data.optBoolean(C.dt.emptyFirstMode, false)
        onEndAction = data.optString(C.dt.onEndAction, null)

        sourceRefresh = { InputObject(this, fetchDetails().conn.inputStream) }
        destRefresh = {
            toOutputObject.invoke(
                if (data.has("destination"))
                    data.getString("destination")
                else null
            )
        }
    }

    override fun toString(): String {
        data.put(C.dt.offset, offset)
        data.put(C.dt.limit, limit)
        data.put("total", total)
        data.put(C.dt.headers, headers)
        data.put(C.dt.url, url)
        return data.str
    }

    var response: ResponseData? = null
    var onFetchBegin: ((obj: DownloadObject) -> Unit)? = null
    var onFetchSuccess: ((obj: DownloadObject, response: ResponseData?) -> Unit)? = null
    var onFetchFailed: ((obj: DownloadObject, e: Exception) -> Unit)? = null

    var isFetchRunning = false
    fun startFetchingDetails(bytesToBeRead: Int = 0) {
        thread {
            startedTime = now()
            log("DownloadObject", "startFetchingDetails: invoking onFetchBegin", "id=$id")
            onFetchBegin?.invoke(this)
            try {
                if (isDownloadCompleted()) {
                    stopTime = now()
                    log(
                        "DownloadObject",
                        "startFetchingDetails: Download already completed",
                        "id=$id"
                    )
                    onFetchSuccess?.invoke(this, null)
                    onSuccess?.invoke(this)
                    executeAction(onEndAction)
                } else {
                    isFetchRunning = true
                    response = fetchDetails(bytesToBeRead)
                    isFetchRunning = false
                    onFetchSuccess?.invoke(this, response!!)
                }
            } catch (e: Exception) {
                log(
                    "DownloadObject",
                    "startFetchingDetails: ",
                    e,
                    "Invoking onFetchFailed",
                    "id=$id"
                )
                stopTime = now()
                onFetchFailed?.invoke(this, e)
            } finally {
                isFetchRunning = false
                log("DownloadObject", "startFetchingDetails: completed", "id=$id")
            }
        }
    }

    private fun isDownloadCompleted(): Boolean {
        val end = limit orElse total
        log(
            "DownloadObject",
            "isDownloadCompleted: end=$end offset=$offset",
            "(end-offset=${end - offset}) writeTotal=$writeTotal",
            "id=$id",
            "result = " + (end - offset == writeTotal)
        )
        return end - offset == writeTotal
    }

    private infix fun Long.orElse(i: Long): Long {
        val min = min(this, i)
        return if (min == -1L) max(this, i) else min
    }

    private fun fetchDetails(bytesToBeRead: Int = 0, recursionIndex: Int = 0): ResponseData {
        val response = ResponseData(URL(url).openConnection() as HttpURLConnection)
        response.conn.connectTimeout = 10000
        response.conn.readTimeout = 10000
        setRequestHeaders(response)
        response.conn.connect()
        if (bytesToBeRead != 0) response.bytes = response.conn.readNBytes(bytesToBeRead)
        response.code = response.conn.responseCode
        fetchResponseHeaders(response)
        if ("Set-Cookie" in response.responseHeaders && recursionIndex < 15) {
            val cookieManager = CookieManager.getInstance()
            val previousCookie = cookieManager.getCookie(url)
            cookieManager.setCookie(url, response.responseHeaders["Set-Cookie"])
            cookieManager.flush()
            val newCookie = cookieManager.getCookie(url)
            if (previousCookie != newCookie) {
                log("DownloadObject", "fetchDetails: re-requesting with cookie")
                response.requestHeaders["Cookie"] = cookieManager.getCookie(url)
                response.requestHeadersString.also {
                    data.put(C.dt.headers, it)
                    headers = it
                }
                return fetchDetails(bytesToBeRead, recursionIndex + 1)
            }
        }
        total = response.total; limit = response.limit
        source = InputObject(this, response.conn.inputStream)

        return response
    }

    /**
     * reads N bytes of this file (from applied offset)
     * @param N = max amount of bytes to be read
     * @return first N bytes of the file if available file size >= `size`
     * @return whole available byteArray, if file size < 50KB
     * @return blank byteArray in case of error
     */
    private fun HttpURLConnection.readNBytes(N: Int): ByteArray {
        val ip = inputStream
        return try {
            val bfr = ByteArray(N)
            var lastRead = ip.read(bfr)
            if (lastRead == -1) return ByteArray(0)
            var total = 0
            while (total < N) {
                total += lastRead
                lastRead = ip.read(bfr, total, N - total)
                if (lastRead == -1) break
            }
            bfr[0 to total]
        } catch (e: Exception) {
            log("DownloadObject", "readNBytes: exception", "id=$id", e)
            ByteArray(0)
        }
    }

    private fun fetchResponseHeaders(response: ResponseData) {
        val set = response.conn.headerFields.entries
        for (map in set) {
            val key = map.key
            val value = map.value.joinToString(separator = "\n")
            if (key != null) response.responseHeaders[key] = value
            else response.responseHeaders["status"] = value
        }
    }

    private fun setRequestHeaders(response: ResponseData) {
        response.conn.setRequestProperty("Connection", "Keep-Alive")
        response.requestHeaders["Connection"] = "Keep-Alive"

        response.conn.setRequestProperty("Accept-Encoding", "utf-8")
        response.requestHeaders["Accept-Encoding"] = "utf-8"

        response.conn.setRequestProperty("Accept-Charset", "utf-8")
        response.requestHeaders["Accept-Charset"] = "utf-8"

        response.conn.setRequestProperty(
            "Accept",
            "multipart/mixed,text/html,image/png,image/jpeg,image/gif,image/x-xbitmap,application/vnd.oma.dd+xml,*/*"
        )
        response.requestHeaders["Accept"] =
            "multipart/mixed,text/html,image/png,image/jpeg,image/gif,image/x-xbitmap,application/vnd.oma.dd+xml,*/*"

        headers?.also {
            val headersArr = it.split("\n")
            for (s in headersArr) {
                val split = s.split(": ")
                if (split.size < 2) continue
                val (key, value) = split[0] to split[1]
                response.conn.setRequestProperty(key, value)
                response.requestHeaders[key] = value
            }
        }
        if (!response.requestHeaders.containsKey("User-Agent")) {
            response.conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 7.0; IF9003 Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.83 Mobile Safari/537.36"
            )
            response.requestHeaders["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 7.0; IF9003 Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.83 Mobile Safari/537.36"
        }

        val offset = this.offset + written
        response.conn.setRequestProperty(
            "Range",
            "bytes=$offset-${if (limit == -1L) "" else (limit - 1).str}"
        )
        response.requestHeaders["Range"] =
            "bytes=$offset-${if (limit == -1L) "" else (limit - 1).str}"
        log("DownloadObject", "setRequestHeaders: offset=$offset; limit=$limit", "id=$id")
    }

    override fun startTransfer() {
        // onStartIO?.invoke() is called from this to ensure that next download is started
        // even if some exception is thrown from here
        val response = response ?: throw RuntimeException(
            "You need to fetch details before starting transfer"
        ).also { onStartIO?.invoke() }
        if (offset + writeTotal != response.offset) throw RuntimeException(
            "Offset mismatch: expected=${offset + writeTotal}, received=${response.offset}"
        ).also { onStartIO?.invoke() }
        if (limit != -1L && limit != response.limit) throw RuntimeException(
            "Limit mismatch: expected=$limit, received=${response.limit}"
        ).also { onStartIO?.invoke() }
        if (total != -1L && total != response.total) throw RuntimeException(
            "Total mismatch: expected=$total, received=${response.total}"
        ).also { onStartIO?.invoke() }

        destination?.close()
        startedTime = Long.MAX_VALUE
        stopTime = Long.MAX_VALUE
        succeededTime = Long.MAX_VALUE
        failedTime = Long.MAX_VALUE
        pauseTime = Long.MAX_VALUE
        resumeTime = Long.MAX_VALUE
        log("DownloadObject", "startTransfer: starting transfer", "id=$id")
        super.startTransfer()
    }

    class ResponseData(val conn: HttpURLConnection) {
        var code = -1
        var bytes: ByteArray = ByteArray(0)
        var responseHeaders = HashMap<String, String>()
        val responseHeadersString: String
            get() = asHeaderText(responseHeaders)
        var requestHeaders = HashMap<String, String>()
        val requestHeadersString: String
            get() = asHeaderText(requestHeaders)
        val filteredReqHeaders: Map<String, String>
            get() = requestHeaders.filter { it.key != "Cookie" && it.key != "Referer" }
        val filteredResHeaders: Map<String, String>
            get() = responseHeaders.filter { it.key != "Set-Cookie" }

        val offset: Long
            get() {
                val range = responseHeaders["Content-Range"] ?: return 0
                if (range.contains(Regex("bytes .+-.+/.+")))
                    return range.split("-")[0].substring(6).toLong()
                return 0
            }
        val limit: Long
            get() {
                val range = responseHeaders["Content-Range"] ?: return -1
                if (range.contains(Regex("bytes .+-.+/.+")))
                    return range.split("-")[1].split("/")[0].toLong() + 1L
                return -1
            }
        val total: Long
            get() {
                val range = responseHeaders["Content-Range"]
                if (range != null && range.contains(Regex("bytes .+-.+/.+")))
                    silently { return range.split("-")[1].split("/")[1].toLong() }
                return responseHeaders["Content-Length"]?.toLong() ?: -1
            }

        val isContentLengthType: Boolean
            get() {
                return responseHeaders["Content-Range"] == null
            }

        val isWebPage: Boolean
            get() {
                return responseHeaders["Content-Type"]?.startsWith("text/html") ?: false
            }

        val fileName: String
            get() {
                val disposition = responseHeaders["Content-Disposition"]
                var name = "file"
                try {
                    if (disposition != null && disposition.contains("filename=\"")) {
                        name = disposition.split("filename=\"")[1].split("\"")[0]
                    } else {
                        log("getFileName", "no content-disposition = $disposition")
                        name = conn.url.file.split("?")[0]
                        if (name.startsWith('/')) name = name.substring(1)
                        if (name.endsWith('/')) name = name.substring(0, name.length - 1)
                        if (name.contains("/")) name = name.substring(name.lastIndexOf("/"))
                    }
                } catch (e: Exception) {
                    log("getFileName", e.str)
                }
                return name.asSanitizedFileName()
            }

        fun downloadSize() = (total orElse limit) - offset

        private fun asHeaderText(headersMap: Map<String, String>): String {
            val builder = StringBuilder()
            for (i in headersMap) {
                //"Range" may conflict with offset & limit
                if (i.key == "Range") continue
                builder.append("${i.key}: ${i.value}\n")
            }
            if (builder.endsWith("\n")) builder.removeSuffix("\n")
            return builder.str
        }

        fun matchesHeaders(headers: String): Boolean {
            val headersArr = headers.split("\n")
            for (s in headersArr) {
                val split = s.split(": ")
                if (split.size < 2) continue
                val (key, value) = split[0] to split[1]
                if (requestHeaders[key] != value) return false
            }
            return true
        }

        override fun toString(): String {
            return "Response code: $code\n" +
                    "Request headers: $filteredReqHeaders\n" +
                    "Response headers: $filteredResHeaders\n" +
                    "is webPage: $isWebPage\n" +
                    "Offset: $offset\n" +
                    "Limit: $limit\n" +
                    "Total: $total\n" +
                    "isContentLengthType: $isContentLengthType"
        }
    }
}