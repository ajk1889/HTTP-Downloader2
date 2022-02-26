package resonance.http.httpdownloader.core

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.BuildConfig
import resonance.http.httpdownloader.helpers.db.LogItem
import java.io.FileNotFoundException
import java.io.IOException
import java.net.*
import java.util.*
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * formats a file size in bytes to easily recognizable KB, MB, GB etc
 * for
 * @param size = 1024*1024*12
 * @param precision = 3
 * @param sep = "--"
 * @return = "12.000--MB"
 */
fun formatSize(size: Double, precision: Int = 1, sep: String = ""): String {
    val availableUnits = arrayOf("bytes", "KB", "MB", "GB", "TB")
    if (size < 0) return "---"
    var s = size
    var i = 0
    while (s > 999 && i < availableUnits.size - 1) {
        s /= 1024L; i++
    }
    val value = if (i == 0) s.toInt().str
    else "%.${precision}f".format(s)
    val unit = availableUnits[i]
    return "$value$sep$unit"
}

fun formatSize(size: Long, precision: Int = 1, sep: String = ""): String {
    return formatSize(size.toDouble(), precision, sep)
}

fun formatTimeDetailed(milliSeconds: Long): String {
    if (milliSeconds > 7.days) return ""
    var remainingMs = milliSeconds
    val days = floor(remainingMs / 1.days + 1e-10).toInt()
    remainingMs -= days.days
    val hours = floor(remainingMs / 1.hours + 1e-10).toInt()
    remainingMs -= hours.hours
    val minutes = floor(remainingMs / 1.minutes + 1e-10).toInt()
    remainingMs -= minutes.minutes
    val seconds = floor(remainingMs / 1.seconds + 1e-10).toInt()
    remainingMs -= seconds.seconds
    var result = ""
    if (days > 0) result = result + days + "D:"
    if (hours > 0) result = result + hours + "H:"
    if (minutes > 0) result =
        if (minutes < 0) result + "0" + minutes + "m:" else result + minutes + "m:"
    result = if (seconds < 10) result + "0" + seconds + "s" else result + seconds + "s"
    return result
}

fun formatTimeMinimal(milliSeconds: Long): String = when {
    milliSeconds < 1.minutes -> "${milliSeconds / 1.seconds} sec"
    milliSeconds < 1.hours -> "${milliSeconds / 1.minutes} min"
    milliSeconds < 1.days -> "${milliSeconds / 1.hours} hrs"
    milliSeconds < 7.days -> "${milliSeconds / 1.days} days"
    else -> ""
}

/**
 * @return detailed cause of exception, to be shown when transfer fails
 */
fun getExceptionCause(e: Exception?, from: String): String {
    if (e?.message?.contains("File descriptor closed") != true)
        log("getExceptionCause", "called from $from with ", e)
    return if (from == "joiner") when {
        e is IOException -> when {
            e.message?.contains("EFBIG") == true -> "4GB+ files aren't supported in this storage medium. " +
                    "Please use file joiner for PC to join huge files. " +
                    "<i>(Go to settings & click on <b>know more</b> to download it)</i>"
            e.message?.contains("ENOSPC") == true ->
                "No more storage space left on device"
            else -> {
                log("Unknown exception", "getExceptionCause: ", e)
                e.message ?: e.str
            }
        }
        e is FileNotFoundException -> "Error accessing the selected files. Please try again"
        e is IllegalArgumentException && e.message.equals("Media is read-only", true) ->
            "The selected output file is not writable. Please select file from a different location"
        else -> {
            log("Unknown exception", "getExceptionCause: ", e)
            e?.message ?: e.str
        }
    } else when {
        e == null -> "Unknown error"
        from == "fetch_failed" && e.str.contains("Failed to connect") ->
            "Failed to connect to server.\nCheck your network & download link"
        e is MalformedURLException ->
            "Your download URL is invalid. Please refresh it by clicking <b>edit icon</b>"
        e is UnknownHostException || e is ConnectException ->
            "Can't connect to server. Check whether your url is correct"
        e is FileNotFoundException ->
            "The server didn't send any data. <b>Link might be expired.</b><br/>" +
                    "Click on <b>edit icon</b> (&#9998;) to refresh download link"
        e is IllegalArgumentException && e.message.equals("Media is read-only", true) ->
            "The selected file is not writable. Please select file from a different location"
        e is RuntimeException -> when {
            e.message?.startsWith("Invalid outputFolder:") == true ->
                "<b>The selected output folder is invalid.</b> Download cannot proceed"
            e.message?.startsWith("Offset mismatch: ") == true ->
                "<b>The server is not returning file from requested position.</b><br/>" +
                        "Click on <b>edit icon</b> (&#9998;) to refresh download link"
            e.message?.startsWith("Limit mismatch: ") == true ->
                "<b>The server returned more data than requested.</b><br/>" +
                        "Click on <b>edit icon</b> (&#9998;) to refresh download link"
            e.message == "'limit' or 'total' need to be set before starting download" ->
                "<b>Server is not sending file size. Link might be expired</b><br/>" +
                        "Click on <b>edit icon</b> (&#9998;) to refresh download link"
            e.message == "This transfer is already running" ->
                "<b>Internal error</b>. Please exit app and try again"
            else -> e.str
        }
        e is IOException -> when {
            e.message?.contains("ENOSPC") == true ->
                "No more storage space left"
            e.message?.contains("EFBIG") == true ->
                "You can't download files bigger than <b>4GB</b> to current storage media. <br/>" +
                        "<b>Download as several parts</b> to solve this problem"
            else -> "Network connection lost"
        }
        e is SocketTimeoutException -> "Network connection lost"
        else -> {
            log("Unknown exception", "getExceptionCause: ", e)
            e.str
        }
    }
}

fun String.shorten(len: Int = 27): String {
    if (this.length <= len) return this
    if (len < 5) return this[0 to 5]
    val middle = if (len % 2 == 1) "..." else "...."
    return this[0 to (len - 3) / 2] + middle + this[length - (len - 3) / 2 to length]
}

private val ioScope = CoroutineScope(Dispatchers.IO)
private val mutex = Mutex()

/**
 * prints all inputs to stdout with
 * @param tag = TAG (print starts with "tag:")
 * @param objects = all datum to be printed
 * @param sep = separate objects by this value
 * @param end = this value will be printed at end
 */
fun log(tag: String = "", vararg objects: Any?, sep: String = " ", end: String = "\n") {
    val now = now()
    val log = toLogString(tag = tag, objects = *objects, sep = sep, end = end)
    println(log.first + ": " + log.second)
    ioScope.launch {
        mutex.withLock {
            silently { ApplicationClass.logDB.insert(LogItem(log.first, log.second, now)) }
        }
    }
}

fun <T> Iterator<T>.list(): MutableList<T> {
    val list = mutableListOf<T>()
    for (item in this) list.add(item)
    return list
}

// type <item>.nlt to get prediction
fun Any?.nuLliTy() = if (this == null) "null" else "not null"
val Any?.str get() = toString()

fun toLogString(
    tag: String = "",
    vararg objects: Any?,
    sep: String = "; ",
    end: String = "\n"
): Pair<String, String> {
    val builder = StringBuilder()
    try {
        if (!BuildConfig.DEBUG || objects.isNotEmpty() && objects[0] !is Throwable)
            builder.append("$tag: ")
        repeat(objects.size - 1) {
            val item = objects[it]
            if (item is Throwable && BuildConfig.DEBUG)
                builder.append("\n$tag(exc): ${Log.getStackTraceString(item)}\n$tag(rem):")
            else builder.append("$item$sep")
        }
        if (objects.isNotEmpty()) {
            val item = objects.last()
            if (item is Throwable && BuildConfig.DEBUG)
                builder.append("\n$tag(exc): ${Log.getStackTraceString(item)}")
            else builder.append("$item$end")
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return builder.str.trim().run {
        try {
            tag to substring(indexOf(tag) + tag.length + 2)
        } catch (e: Exception) {
            tag to this
        }
    }
}

/**
 * converts a file name to name-extension pair
 * extension may be null if not present
 */
fun String.toNameNExtension(): Pair<String, String?> {
    return if (this.contains(".")) {
        this.lastIndexOf('.').let {
            if (this.endsWith('.')) return this[0 to it] to ""
            else this[0 to it] to this[it + 1 to length]
        }
    } else this to null
}

/**
 * adds part number to the name of a required file part
 * @param name = file part name (sample "file.extension")
 * @param num = part number (sample "-part1")
 * @return fileName with part number added to it. Eg: "file-part1.extension"
 */
fun addPartNumTo(name: String, num: Int): String = addSuffixToFileName(name, " (part$num)")

/**
 * @return a new name for file assuming a file with name
 * @param fileName already exist
 * This function need to be recursively executed to ensure that no file with new name exists too
 */
fun renameDuplicateFile(fileName: String): String {
    if (fileName.contains(Regex("-\\d+\\.[^.]*$"))) { //already renamed file having an extension
        var (name, ext) = fileName.toNameNExtension()
        with(name.lastIndexOf('-')) {
            val n = name[this + 1 to name.length].toInt()
            name = name[0 to this]
            return "$name-${n + 1}.$ext"
        }
    } else {
        val (name, ext) = fileName.toNameNExtension()
        //file not previously renamed, but have an extension
        if (ext != null) return "$name-1.$ext"

        return if (fileName.contains(Regex("-\\d+$"))) { //already renamed file having no other extension
            val lst = fileName.split("-")
            val fName = lst.subList(0, lst.size - 1).joinToString("-")
            val n = lst.last().toInt() + 1
            "$fName-$n"
        } else "$fileName-1" //file not previously renamed and don't have an extension
    }
}

/**
 * Returns offset & limit for {@param index}th device,
 * while downloading a file of size {@param total}
 * using {@param count} devices
 */
fun getRangeOf(index: Int, count: Int, total: Long): Pair<Long, Long> {
    if (index < 0 || count < 0 || total < 0) throw RuntimeException(
        "All of index($index), total($total) and count($count) should be positive integers"
    )
    val limit = total % count + (total / count) * index
    val offset = if (index == 1) 0 else limit - (total / count)
    return offset to limit
}

infix fun Long.orElse(i: Long): Long {
    val min = min(this, i)
    return if (min == -1L) max(this, i) else min
}

val mimeTypeMap = mapOf(
    "aac" to "audio/aac",
    "abw" to "application/x-abiword",
    "arc" to "application/x-freearc",
    "avi" to "video/x-msvideo",
    "azw" to "application/vnd.amazon.ebook",
    "bin" to "application/octet-stream",
    "bmp" to "image/bmp",
    "bz" to "application/x-bzip",
    "bz2" to "application/x-bzip2",
    "csh" to "application/x-csh",
    "css" to "text/css",
    "csv" to "text/csv",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "eot" to "application/vnd.ms-fontobject",
    "epub" to "application/epub+zip",
    "gz" to "application/gzip",
    "gif" to "image/gif",
    "htm" to "text/html",
    "html" to "text/html",
    "ico" to "image/vnd.microsoft.icon",
    "ics" to "text/calendar",
    "jar" to "application/java-archive",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "js" to "text/javascript",
    "json" to "application/json",
    "jsonld" to "application/ld+json",
    "mid" to "audio/midi audio/x-midi",
    "midi" to "audio/midi audio/x-midi",
    "mjs" to "text/javascript",
    "mp3" to "audio/mpeg",
    "mpeg" to "video/mpeg",
    "mpkg" to "application/vnd.apple.installer+xml",
    "ppt" to "application/vnd.ms-powerpoint",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odt" to "application/vnd.oasis.opendocument.text",
    "oga" to "audio/ogg",
    "ogv" to "video/ogg",
    "ogx" to "application/ogg",
    "opus" to "audio/opus",
    "otf" to "font/otf",
    "png" to "image/png",
    "pdf" to "application/pdf",
    "php" to "application/php",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "rar" to "application/x-rar-compressed",
    "rtf" to "application/rtf",
    "sh" to "application/x-sh",
    "svg" to "image/svg+xml",
    "swf" to "application/x-shockwave-flash",
    "tar" to "application/x-tar",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "ts" to "video/mp2t",
    "ttf" to "font/ttf",
    "txt" to "text/plain",
    "vsd" to "application/vnd.visio",
    "wav" to "audio/wav",
    "weba" to "audio/webm",
    "webm" to "video/webm",
    "webp" to "image/webp",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "xhtml" to "application/xhtml+xml",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xml" to "text/xml",
    "xul" to "application/vnd.mozilla.xul+xml",
    "zip" to "application/zip",
    "3gp" to "video/3gpp",
    "3g2" to "video/3gpp2",
    "7z" to "application/x-7z-compressed"
)

fun toMimeType(fileName: String): String {
    val extension = fileName.toNameNExtension().second
        ?.lowercase(Locale.getDefault()) ?: ""
    return mimeTypeMap[extension] ?: URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
}

/**
 * For sorting purpose; Accepts following regex in file names to be sorted
 * If regex is absent, returns 0
 * \(part\d+\)
 * \([0-9.]+k?m?g?b(ytes)?-[0-9.]+k?m?g?b(ytes)?\)
 * \(\d+ of \d+\)
 */
@SuppressLint("DefaultLocale")
fun nameToComparableLong(name: String): Long {
    val reg1 = Regex(""" \(part(\d+)\)""")
    val reg2 = Regex(
        """ \(([0-9.]+)(k?m?g?t?b(ytes)?)-([0-9.]+)(k?m?g?t?b(ytes)?)\)""",
        RegexOption.IGNORE_CASE
    )
    val reg3 = Regex(""" \((\d+) of \d+\)""")
    try {
        reg1.find(name)?.also { result ->
            return result.destructured.component1().toLong()
        }
        reg2.find(name)?.also { result ->
            val pow = mapOf("bytes" to 0, "kb" to 1, "mb" to 2, "gb" to 3, "tb" to 4)
            val (c1, c2, _, c4, c5) = result.destructured
            val from =
                c1.toDouble() * 1024.0.pow(pow[c2.lowercase(Locale.getDefault())] ?: -1000000)
            val to = c4.toDouble() * 1024.0.pow(pow[c5.lowercase(Locale.getDefault())] ?: -1000000)
            return (from / 2 + to / 2).toLong()
        }
        reg3.find(name)?.also { result ->
            return result.destructured.component1().toLong()
        }
    } catch (ignored: Exception) { // to avoid crash in case of number format error
    }
    return 0
}

val Int.GB
    get() = this * 1024L * 1024L * 1024L
val Int.MB
    get() = this * 1024 * 1024
val Int.KB
    get() = this * 1024
val Double.GB
    get() = this * 1024L * 1024L * 1024L
val Double.MB
    get() = this * 1024L * 1024L
val Double.KB
    get() = this * 1024L

val Int.days
    get() = this * 24L * 3600L * 1000L
val Int.minutes
    get() = this * 60 * 1000
val Int.hours
    get() = this * 3600 * 1000
val Int.seconds
    get() = this * 1000

fun addSuffixToFileName(fileName: String, suffix: String): String {
    val point = fileName.lastIndexOf('.')
    return if (fileName.contains('.'))
        fileName[0 to point] + suffix + fileName[point to fileName.length]
    else fileName + suffix
}

fun now() = System.currentTimeMillis()
fun preciseNow() = System.nanoTime()

fun String.trimToMaxLength(length: Int): String {
    return if (this.length < length) this
    else this[0 to length]
}

fun JSONObject.hasAll(vararg keys: String): Boolean {
    for (k in keys)
        if (!this.has(k))
            return false
    return true
}

inline fun <T> silently(block: () -> T) {
    try {
        block()
    } catch (e: Throwable) {
    }
}

infix fun <T : Any?> (() -> T).otherwise(default: T): T = try {
    this()
} catch (e: Throwable) {
    default
}

operator fun <T> MutableList<T>.get(range: Pair<Int, Int>): MutableList<T> {
    val from = if (range.first < 0) size + range.first else range.first
    val to = if (range.second < 0) size + range.second else range.second
    return this.subList(from, to)
}

operator fun ByteArray.get(range: Pair<Int, Int>): ByteArray {
    val from = if (range.first < 0) size + range.first else range.first
    val to = if (range.second < 0) size + range.second else range.second
    return range.run { ByteArray(to - from) { this@get[from + it] } }
}

operator fun String.get(range: Pair<Int, Int>): String {
    val from = if (range.first < 0) length + range.first else range.first
    val to = if (range.second < 0) length + range.second else range.second
    return substring(from, to)
}

fun CoroutineScope.start(asyncFunction: () -> Unit) {
    launch { asyncFunction() }
}

class UnreachablePoint(private val index: Int) : java.lang.Exception() {
    override fun toString(): String {
        return "Unreachable point $index"
    }
}

fun <T> MutableCollection<T>.removeUntil(condition: (item: T) -> Boolean) {
    while (this.isNotEmpty() && condition(this.first()))
        this.remove(this.first())
}

data class BytesAtInstant(val bytesRead: Long, val timeStamp: Long)

infix fun Long.at(timeStamp: Long) = BytesAtInstant(this, timeStamp)