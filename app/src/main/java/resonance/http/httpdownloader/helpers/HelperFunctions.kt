package resonance.http.httpdownloader.helpers

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass.Companion.lastReceivedIntentId
import resonance.http.httpdownloader.ApplicationClass.Companion.lastSentIntentId
import resonance.http.httpdownloader.ConnectionChecker
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.ParentActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.implementations.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.*
import kotlin.math.min
import kotlin.math.pow

val please get() = "UGxlYXNlIA=="

fun configsFolder(ctx: Context) = File(ctx.filesDir, "configs").apply {
    if (!exists()) mkdirs()
}

fun getUniqueId(): Long {
    return ++Pref.lastDownloadId
}

fun Uri?.getDisplayName(ctx: Context): String? {
    if (this == null) return null
    val folder = DocumentFile.fromTreeUri(ctx, this) ?: return null
    return folder.name
}

fun pingGoogle(): Boolean = {
    val conn = URL("https://www.google.com").openConnection() as HttpURLConnection
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
    conn.connect()
    true
} otherwise false

fun Context.onConnectivityRestored(onComplete: (() -> Unit)? = null) {
    val uiScope = CoroutineScope(Dispatchers.Main)
    suspend fun suspendPingGoogle(): Boolean = withContext(Dispatchers.IO) { pingGoogle() }

    log("Helper", "onConnectivityRestored")
    if (!Pref.autoRetryDownloads || Pref.retryList.isEmpty()) return
    val ctx = this@onConnectivityRestored
    uiScope.launch {
        val isConnectionLive = suspendPingGoogle()
        if (isConnectionLive) {
            val helper = TransferServiceConnection(ctx)
            val intent = Intent(C.filter.REQUEST)
            intent.putExtra(C.misc.request, C.req.retryFailed)
            helper.request(intent)
            log("onConnectivityRestored", "retryFailed broadcast sent")
        } else {
            val intent = Intent(ctx, ConnectionChecker::class.java)
            ContextCompat.startForegroundService(ctx, intent)
            log("onConnectivityRestored", "no internet, started ConnectionChecker service")
        }

        onComplete?.invoke()
    }
}

fun Context.isNetworkLive(): Boolean = with(
    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
) { return this?.activeNetworkInfo?.isConnected == true }

fun EditText.setOnTextChangedListener(onChange: (text: Editable?) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange(s)
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

fun String.asHtml(): Spanned = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY)

fun ArrayAdapter<TransferWrapper>.findItemById(id: Long): TransferWrapper {
    for (i in 0 until count) {
        val item = getItem(i) ?: continue
        if (id == item.id) return item
    }
    throw RuntimeException("No items with id=$id exists in $this")
}

fun String.decode() = String(Base64.decode(this, Base64.DEFAULT))

fun Context.showShortToast(msg: String): Toast =
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }

fun Context.showLongToast(msg: String): Toast =
    Toast.makeText(this, msg, Toast.LENGTH_LONG).also { it.show() }

fun filePreExists(file: String, folder: String, ctx: Context): Boolean {
    return when {
        folder.isFile() ->
            File(folder.toFolder(), file).exists()
        folder.isUri() ->
            DocumentFile.fromTreeUri(ctx, folder.toUri())?.findFile(file) != null
        else -> false
    }
}

@Synchronized
fun Intent.isDuplicate(): Boolean {
    val intentId = this.id
    return (intentId < lastReceivedIntentId).also {
        if (it) {
            if (lastReceivedIntentId > lastSentIntentId) { //Should never happen
                lastReceivedIntentId = 0; lastSentIntentId = 0
                log(
                    "IntentId mismatch",
                    "lastReceivedIntentId($lastReceivedIntentId)>lastSentIntentId($lastSentIntentId)",
                    "resetting values to zero"
                )
                return@isDuplicate false
            } else log(
                "duplicate intent",
                "id=$intentId lastReceivedIntentId=$lastReceivedIntentId"
            )
        }
    }
}

suspend fun generateQRCode(string: String, size: Int): Bitmap? = withContext(Dispatchers.Default) {
    fun setPixel(bitmap: Bitmap, bitMatrix: BitMatrix, from: Int, to: Int) {
        for (x in from until to)
            for (y in 0 until size)
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.TRANSPARENT)
    }

    val writer = QRCodeWriter()
    val bit = writer.encode(string, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_4444) ?: return@withContext null
    val defaultScope = CoroutineScope(Dispatchers.Default)
    val coroutines = listOf(
        defaultScope.launch { setPixel(bitmap, bit, 0, size / 4) },
        defaultScope.launch { setPixel(bitmap, bit, size / 4, size / 2) },
        defaultScope.launch { setPixel(bitmap, bit, size / 2, 3 * size / 4) },
        defaultScope.launch { setPixel(bitmap, bit, 3 * size / 4, size) }
    )
    for (i in coroutines) i.join()
    bitmap
}

fun String.asSanitizedUrl() = with(this.trim()) {
    if (!(startsWith("http://") || startsWith("https://") || startsWith("file://"))) {
        if (contains(".") && (!contains(" ") || contains("/"))
            || matches(Regex(".+:\\d+.*"))
        ) "http://$this"
        else "https://www.google.com/search?q=" + Uri.encode(this)
    } else this
}

fun String.asSanitizedFileName(): String {
    var op = URLDecoder.decode(this, "utf8")
    val non = "*/\\\"';{}^%$`:?|<>"
    for (i in non) op = op.replace(i, '_')
    return op
}

fun String?.isValidUrl(): Boolean {
    if (this == null) return false
    return { URL(this).toURI(); true } otherwise false
}

fun Bitmap.toBase64(): String {
    val byteArrayOp = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOp)
    return Base64.encodeToString(byteArrayOp.toByteArray(), Base64.DEFAULT)
}

fun String.toBitmap(): Bitmap {
    val bytes = Base64.decode(this, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

fun LinearLayout.setAnimateOnLayoutChanges(animate: Boolean) {
    layoutTransition = if (animate) LayoutTransition()
    else LayoutTransition().apply {
        disableTransitionType(LayoutTransition.DISAPPEARING)
        disableTransitionType(LayoutTransition.APPEARING)
        disableTransitionType(LayoutTransition.CHANGE_APPEARING)
        disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
    }
}

@SuppressLint("InflateParams")
fun getDownloadVisual(activity: Activity, total: Long, offset: Long, limit: Long): View {
    val view = activity.layoutInflater.inflate(R.layout.download_visual, null)
    val from = if (offset == 0L) "start" else formatSize(offset)
    val to = if (limit == total) "end" else formatSize(limit)
    view.findViewById<TextView>(R.id.text1).text = from
    view.findViewById<TextView>(R.id.text2).text = to
    val w0 = offset * 1f / total
    val w2 = (total - (limit orElse total)) * 1f / total
    val w1 = 1 - w0 - w2
    view.findViewById<View>(R.id.view0).apply {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w0 }
    }
    view.findViewById<View>(R.id.view1).apply {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w1 }
    }
    view.findViewById<View>(R.id.view2).apply {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w2 }
    }
    return view
}

fun Activity.getClipboardText() = {
    val manager = getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
    manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.str
} otherwise null

fun Activity.pasteClipBoardTo(editText: EditText): Boolean {
    silently {
        val text = getClipboardText()
        val json = JSONObject(text ?: "{}")
        if (json.has("url")) {
            editText.setText(text)
            editText.selectAll()
            return true
        }
    }
    return false
}

fun Activity.showConfirmSendMailAlert(onYes: () -> Unit) {
    val message = """
        Are you sure you want to mail us? <small>Expected reply time: less than 48 hours</small>
        <br/><br/>
        <b>Notes:</b><br/>
        <small>
            &bull;&nbsp;Be specific on the topic<br/>
            &bull;&nbsp;If you have any problem using our app, explain it in detail; <b>note that we need to see the same issue on any one of our test device at least once</b> to get it fixed<br/>
            &bull;&nbsp;When raising a feature request, make sure it is in the scope of this app. (Features like: <i>perform an anti-virus scan on the downloaded file</i>, will be rejected!)<br/>
            &bull;&nbsp;<font color='red'><b>Please do not spam us</b></font>. We may ignore, or even permanently block E-mail addresses from which we receive messages that aren't relevant to HTTP-Downloader
        </small>
    """.trimIndent()
    AlertDialog.Builder(this)
        .setTitle("Send mail?")
        .setMessage(message.asHtml())
        .setPositiveButton("Cancel") { d, _ -> d.dismiss() }
        .setNeutralButton("Send mail") { d, _ -> onYes(); d.dismiss() }
        .show()
}

fun ParentActivity.contactUs(subject: String, text: String) = showConfirmSendMailAlert {
    val intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "resonance00x0@gmail.com", null))
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.putExtra(Intent.EXTRA_TEXT, text)
    try {
        startActivity(Intent.createChooser(intent, "Send mail via:"))
    } catch (e: ActivityNotFoundException) {
        showSnackBar("Please install an email app and try again")
    }
}

fun View.hide() {
    visibility = View.INVISIBLE
}

fun View.unHide() {
    visibility = View.VISIBLE
}

fun View.setGone() {
    visibility = View.GONE
}

fun isFAT32Probable(): Boolean {
    val process = Runtime.getRuntime().exec("mount")
    val ip = process.inputStream
    val content = ByteArray(300)
    val byteArrayOP = ByteArrayOutputStream()
    var n = ip.read(content)
    while (n != -1) {
        byteArrayOP.write(content, 0, n)
        n = ip.read(content)
    }
    val result = String(byteArrayOP.toByteArray()).lowercase(Locale.getDefault())
    return result.contains("vfat") || result.contains("fat32")
}

fun Intent.getRWFlags(): Int {
    return this.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

fun TextView.enable() {
    isEnabled = true
    setBackgroundResource(R.drawable.round_blue)
}

fun isSdk29Plus() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

fun Activity.copyToClipBoard(url: CharSequence?) = {
    val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipManager.setPrimaryClip(ClipData.newPlainText("HTTP-Downloader", url))
    true
} otherwise false

fun TextView.disable() {
    isEnabled = false
    setBackgroundResource(R.drawable.round_333)
}

fun Activity.hideKeyboard() {
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
}

const val speedMultiplier = 1.14373276

@SuppressLint("InflateParams", "SetTextI18n")
fun ParentActivity.showSpeedLimitDialog(
    initialSpeed: Int,
    maxSpeedText: String = "unrestricted speed",
    onSpeedUpdated: (Int) -> Unit
): AlertDialog? {
    fun Int.toSpeed() = (1961 + speedMultiplier.pow(this / 10.0 + 60)).toInt()
    fun Int.toProgress() =
        (kotlin.math.log((this - 1961).toDouble(), speedMultiplier).toInt() - 60) * 10
    fun TextView.setSpeed(speed: Int) {
        text = if (speed == Int.MAX_VALUE) maxSpeedText
        else formatSize(speed.toLong(), 2, " ") + "/s"
    }

    val view = layoutInflater.inflate(R.layout.speed_limiter_dialog, null)
    val speedAdjuster = view.findViewById<SeekBar>(R.id.seekBar)
    val speedText = view.findViewById<TextView>(R.id.speedText)
    var newSpeed = initialSpeed

    speedAdjuster.max = 1000
    speedAdjuster.progress = initialSpeed.toProgress()
    speedText.setSpeed(initialSpeed)

    speedAdjuster.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            newSpeed = speedAdjuster.progress.toSpeed()
            speedText.setSpeed(newSpeed)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            newSpeed = speedAdjuster.progress.toSpeed()
        }
    })
    return AlertDialog.Builder(this)
        .setTitle("Limit download speed")
        .setView(view)
        .setPositiveButton("OK") { d, _ ->
            log("showSpeedLimitDialog", "newSpeed: $newSpeed")
            onSpeedUpdated(newSpeed); d.dismiss()
        }
        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        .show()

}
fun Activity.showKeyboard() {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
}

fun String.toFolder() = File(substring(C.type.file.length + 1))
fun String.toUri(): Uri = Uri.parse(substring(C.type.uri.length + 1))
fun String.toUriFile(context: Context) = UriFile(context, substring(C.type.fileUri.length + 1))

fun String.isFile() = startsWith(C.type.file + ":")
fun String.isUri() = startsWith(C.type.uri + ":")
fun String.isUriFile() = startsWith(C.type.fileUri + ":")

var Intent.id: Long
    get() = getLongExtra(C.misc.intentId, -1L)
    set(value) {
        putExtra(C.misc.intentId, value)
    }

operator fun InputStream.plus(second: InputStream): InputStream {
    val first = this
    return object : InputStream() {
        var current = first
        override fun read() = throw RuntimeException("read() should not be used")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (Server.sleep > 0L) Thread.sleep(Server.sleep)
            val r = current.read(b, off, len)
            return if (r < 0 && current == first) {
                current = second
                current.read(b, off, len)
            } else r
        }
    }
}

/**
 * @return an inputStream that terminates when it reaches supplied limit (exclusive)
 */
fun InputStream.toLimitedStream(limit: Long): InputStream {
    val old = this
    return object : InputStream() {
        var bytesRead = 0L
        override fun read() = throw RuntimeException("read() should not be used")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (Server.sleep > 0L) Thread.sleep(Server.sleep)
            if (bytesRead >= limit) return -1
            val length = min(len.toLong(), limit - bytesRead).toInt()
            return old.read(b, off, length).also { bytesRead += it }
        }

        override fun skip(n: Long): Long {
            val length = min(n, limit - bytesRead)
            return super.skip(length).also { bytesRead += it }
        }
    }
}

fun String.escapeHTML(): String {
    val reversed = replace("&amp;", "&")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("<br>", "\n")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
    return reversed.replace("&", "&amp;")
        .replace(">", "&gt;")
        .replace("<", "&lt;")
        .replace("\n", "<br>")
        .replace(">", "&gt;")
        .replace("<", "&lt;")
        .replace("   ", "&nbsp;&nbsp;&nbsp;")
        .replace("  ", "&nbsp;&nbsp;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
fun Long.formatted(): String {
    if (this < 0) return "---"
    var s = this.toDouble()
    var i = 0
    while (s > 999 && i < 4) {
        s /= 1024L; i++
    }
    val value = if (i == 0) s.toInt().str
    else "%.2f".format(s)
    val unit = arrayOf("bytes", "KB", "MB", "GB", "TB")[i]
    return "$value $unit"
}

fun InputStream.readExact(len: Int): ByteArray {
    val op = ByteArrayOutputStream()
    transferTo(op, len)
    return op.toByteArray()
}

fun InputStream.transferTo(op: OutputStream, len: Int) {
    val b = ByteArray(len)
    var n = this.read(b, 0, len)
    var count = n
    while (n != -1) {
        op.write(b, 0, n)
        if (count >= len) break
        n = this.read(b, 0, len - count)
        count += n
    }
}

fun getOutputObject(ctx: Context, task: TransferWrapper) = with(task.outputFolder) {
    when {
        isFile() -> FileIO(ctx, task)
        isUri() -> DocIO(ctx, task)
        isUriFile() -> UriIO(ctx, task)
        else -> throw RuntimeException("Invalid outputFolder: $this")
    }
}

val extIconMap = mapOf(
    "iso" to R.drawable.iso, "dmg" to R.drawable.iso, "img" to R.drawable.iso,
    "mp4" to R.drawable.video, "mkv" to R.drawable.video, "mov" to R.drawable.video,
    "wmv" to R.drawable.video, "webm" to R.drawable.video, "mpeg" to R.drawable.video,
    "avi" to R.drawable.video, "flv" to R.drawable.video, "mpg" to R.drawable.video,
    "ogg" to R.drawable.audio, "mp3" to R.drawable.audio, "aiff" to R.drawable.audio,
    "aac" to R.drawable.audio, "wma" to R.drawable.audio, "wav" to R.drawable.audio,
    "png" to R.drawable.image, "jpg" to R.drawable.image, "jpeg" to R.drawable.image,
    "bmp" to R.drawable.image, "psd" to R.drawable.image, "gif" to R.drawable.image,
    "tiff" to R.drawable.image, "webp" to R.drawable.image,
    "pdf" to R.drawable.pdf, "apk" to R.drawable.apk, "obb" to R.drawable.apk,
    "zip" to R.drawable.archive, "7z" to R.drawable.archive, "rar" to R.drawable.archive,
    "tar" to R.drawable.archive, "egg" to R.drawable.archive, "kgb" to R.drawable.archive,
    "deb" to R.drawable.archive, "rpm" to R.drawable.archive,
    "xz" to R.drawable.archive, "gzip" to R.drawable.archive, "lzip" to R.drawable.archive,
    "sh" to R.drawable.executable, "run" to R.drawable.executable, "exe" to R.drawable.executable,
    "pkg" to R.drawable.executable, "msi" to R.drawable.executable, "bat" to R.drawable.executable,
    "bin" to R.drawable.executable, "dll" to R.drawable.executable, "jar" to R.drawable.executable,
    "py" to R.drawable.text, "js" to R.drawable.text, "java" to R.drawable.text,
    "txt" to R.drawable.text, "cpp" to R.drawable.text, "c" to R.drawable.text,
    "ini" to R.drawable.text, "conf" to R.drawable.text, "html" to R.drawable.text,
    "htm" to R.drawable.text, "css" to R.drawable.text, "php" to R.drawable.text
)

val userAgents = mapOf(
    "Default" to null,
    "Windows" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36",
    "Ubuntu" to "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36",
    "Mac" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36",
    "iPhone" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/83.0.4103.63 Mobile/15E148 Safari/604.1",
    "Chrome" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.60 Mobile Safari/537.36",
    "FireFox" to "Mozilla/5.0 (Android 10; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0"
)
val userAgentNames = mutableListOf<String>().apply {
    userAgents.forEach { this.add(it.key) }
    add("Custom")
}