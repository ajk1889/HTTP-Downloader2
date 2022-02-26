package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.UriFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.NetworkInterface.getNetworkInterfaces
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class TestActivity : AppCompatActivity() {
    companion object {
        const val SELECT_ORIGINAL = 1228
        const val SELECT_TEST = 8921
        const val SCAN_QR = 9127
        lateinit var server: Server
        var isServerInitialized = false
    }

    private var originalInput: InputStream = Generator()
    private lateinit var testInput: InputStream
    private var params = JSONObject()
    private var toast: Toast? = null
    private val adapter: ArrayAdapter<String> by lazy {
        ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
        if (!isServerInitialized) {
            params = readParams()
            server = Server(params.optInt("port", 1234))
            server.onTextShared = ::addToList
            isServerInitialized = true
        }
        sharedTexts.adapter = adapter
        sharedTexts.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, n, _ ->
            if (copyToClipBoard(adapter.getItem(n)))
                showShortToast("Copied")
            else showShortToast("Failed to copy")
            true
        }
        qrcode.setOnClickListener { uiScope.launch { updateQrCode() } }
        qrcode.setOnLongClickListener {
            val intent = Intent(this, QrCodeActivity::class.java)
            this.startActivityForResult(intent, SCAN_QR)
            true
        }
    }

    private fun addToList(text: String?) = adapter.insert(text, 0)

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SELECT_ORIGINAL -> {
                val file = UriFile(this, data?.dataString ?: return)
                orig.text = "Orig: " + file.name.shorten(15)
                originalInput = file.getInputStream()
                silently { testInput.reset() }
            }
            SELECT_TEST -> {
                val file = UriFile(this, data?.dataString ?: return)
                test.text = "Test: " + file.name.shorten(15)
                testInput = file.getInputStream()
                originalInput.apply {
                    silently { this.reset() }
                    if (this is Generator) limit = file.size
                }
            }
            SCAN_QR -> {
                if (resultCode == Activity.RESULT_OK)
                    data?.getStringExtra(C.misc.qrScanResult)?.also { addToList(it) }
            }
        }
    }

    private fun readParams() = {
        val input = FileInputStream(File(getExternalFilesDir(null), "params.json"))
        val bytes = ByteArray(input.available())
        input.read(bytes)
        input.close()
        JSONObject(String(bytes).trim())
    } otherwise JSONObject()

    override fun onPause() {
        super.onPause()
        toast?.cancel()
    }

    private val shownIPs = mutableSetOf<String>()
    private var availableIPs = mutableListOf<String>()
    override fun onResume() {
        super.onResume()
        params = readParams()
        uiScope.launch {
            availableIPs = deviceIp()
            availableIPs.sortBy { if (it.startsWith("192.")) 0 else 1 }
            updateQrCode()
        }
        if (server.isRunning)
            startBtn.text = "Stop server"
        else startBtn.text = "Start server"
    }

    private suspend fun updateQrCode() {
        for (ip in availableIPs) if (!shownIPs.contains(ip)) {
            qrcode.setImageBitmap(generateQRCode("http://$ip:1234/", 125.dp))
            ipAddress.text = ip
            shownIPs.add(ip)
            if (availableIPs.all { shownIPs.contains(it) }) shownIPs.clear()
            break
        }
    }

    fun changeOriginal(view: View) {
        AlertDialog.Builder(this)
            .setTitle("Original file")
            .setPositiveButton("123") { _, _ ->
                originalInput = Generator()
                orig.text = "Orig: 123.txt"
            }
            .setNegativeButton("file") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(
                    Intent.createChooser(intent, "Select original"),
                    SELECT_ORIGINAL
                )
            }
            .show()
    }

    fun changeTest(view: View) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(Intent.createChooser(intent, "Select test"), SELECT_TEST)
    }

    val handler = Handler()
    fun startTest(view: View) {
        logFile = FileOutputStream(File(getExternalFilesDir(null), "testLog.txt"))
        thread {
            var isComplete = true
            var lastTime = now()
            var lastTotal = 0L
            val origBfr = ByteArray(50 * 1024)
            val testBfr = ByteArray(50 * 1024)
            var n = testInput.read(testBfr)
            var m = originalInput.read(origBfr)
            var total = 0L
            while (n > 0) {
                total += n
                val now = now()
                if (now - lastTime > 400) {
                    setStatus("processed ${formatSize(total)} at ${formatSize((total - lastTotal) * 1000 / (now - lastTime))}/s")
                    lastTime = now
                    lastTotal = total
                }
                if (n == m) {
                    if (!testBfr.contentEquals(origBfr)) {
                        log("Length: test=$n, orig=$m\n")
                        log("Orig: ")
                        log(origBfr)
                        log("\n\n\nTest: ")
                        log(testBfr)
                        setStatus("<font color=red>Unequal contents; see logs</font>")
                        isComplete = false
                        break
                    }
                } else if (n < m) {
                    val trimmedOrig = origBfr.copyOf(n)
                    val trimmedTest = testBfr.copyOf(n)
                    if (!trimmedTest.contentEquals(trimmedOrig)) {
                        log("Length: test=$n, orig=$m\n")
                        log("Orig: ")
                        log(trimmedOrig)
                        log("\n\n\nTest: ")
                        log(testBfr)
                        setStatus("Unequal size and <font color=red>Unequal contents; see logs</font>")
                    } else setStatus("Unequal content size; Equal contents")
                    isComplete = false
                    break
                } else {
                    setStatus("<font color=red>Unexpected size at total=$total; test($n)&gt;orig($m)</font>")
                    isComplete = false
                    break
                }
                n = testInput.read(testBfr)
                m = originalInput.read(origBfr)
            }
            if (isComplete) setStatus("Completed")
            logFile.close()
        }
    }

    private fun setStatus(str: String) = handler.post { status.text = str.asHtml() }
    private lateinit var logFile: FileOutputStream
    private fun log(str: String) = logFile.write(str.toByteArray())
    private fun log(bytes: ByteArray) = logFile.write(bytes)

    fun startServer(view: View) {
        if (server.isRunning) {
            server.stop()
            startBtn.text = "Start server"
        } else {
            updateParams(view)
            server.start()
            startBtn.text = "Stop server"
        }
    }

    private val Int.dp: Int
        get() {
            val metrics = resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }

    fun updateParams(view: View) {
        params = readParams()
        if (params.has("htdocs"))
            server.htdocs = File(params.getString("htdocs"))
        if (params.has("path123"))
            server.path123 = params.getString("path123")
        if (params.has("cookie"))
            server.cookies = params.getString("cookie")
        if (params.has("size123"))
            server.size123 = params.getLong("size123")
        if (params.has("ping"))
            server.ping = params.getLong("ping")
        if (params.has("sleep"))
            Server.sleep = params.getLong("sleep")
        if (params.has("bfrsize"))
            server.bufferSize = params.getInt("bfrsize")
        if (params.has("lengthonly"))
            server.contentLengthMode = params.getBoolean("lengthonly")
        if (params.has("nolength"))
            server.noLengthMode = params.getBoolean("nolength")
        if (params.has("logging"))
            server.loggingAllowed = params.getBoolean("logging")
        toast = Toast.makeText(
            this,
            if (params.length() == 0) "Params not added" else "Done",
            Toast.LENGTH_SHORT
        )
        toast?.show()
    }

    private val uiScope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("SimpleDateFormat")
    fun exportLogs(view: View) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS")
        uiScope.launch {
            val toast = showShortToast("exporting...")
            withContext(Dispatchers.IO) {
                log("TestActivity", "exportLogs: start")
                val logs = ApplicationClass.logDB.getAll(50000)
                val op = FileOutputStream(
                    File(
                        Environment.getExternalStorageDirectory(),
                        "http-logs.html"
                    )
                )
                op.write(
                    """
                    |<style>
                    |table, th, td {
                    |  border: 1px solid black;
                    |  border-collapse: collapse;
                    |}
                    |</style>
                    |<table style="width:100%">
                    |<tr>
                    |  <th>id</th>
                    |  <th>time</th>
                    |  <th>tag</th>
                    |  <th>message</th>
                    |</tr>""".trimMargin().toByteArray()
                )
                for (log in logs) {
                    op.write(
                        """
                        |<tr>
                        |<td>${log.id}</td>
                        |<td>${format.format(Date(log.time))}</td>
                        |<td>${log.tag}</td>
                        |<td>${log.msg.escapeHTML()}</td>
                        |</tr>""".trimMargin().toByteArray()
                    )
                }
                op.write("</table>".toByteArray())
                op.close()
                log("TestActivity", "exportLogs: end")
            }
            toast.cancel()
            showShortToast("completed")
        }
    }

    private suspend fun deviceIp(): MutableList<String> = withContext(Dispatchers.IO) {
        val addresses = mutableListOf<String>()
        for (net in getNetworkInterfaces()) for (address in net.inetAddresses) {
            if (!address.isLoopbackAddress) {
                val ip = address.hostAddress
                if (!ip.contains(":")) addresses.add(ip)
            }
        }
        addresses.apply { if (isEmpty()) add("No IP") }
    }
}
