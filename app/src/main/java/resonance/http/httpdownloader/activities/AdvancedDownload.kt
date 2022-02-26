package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_advanced_download.*
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.UriFile
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class AdvancedDownload : ParentActivity() {
    private companion object {
        val MAX_SINGLE_FILE_SIZE = 3.9.GB
        val PREFERRED_SINGLE_FILE_SIZE = 3.5.GB
        const val FOLDER_CHOOSE = 2378
        const val FILE_CHOOSE = 2129
    }

    private var sdCardUri: Uri? = null
    private var uriFile: UriFile? = null

    private lateinit var animFactory: AnimFactory
    private lateinit var transferServiceConnection: TransferServiceConnection
    private var isProgrammaticCheckChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_download)
        log("AdvancedDownload", "oCr:")

        animFactory = AnimFactory()

        if (Pref.isAdvancedMode1stTime) {
            showWelcomeMessage()
        }

        transferServiceConnection = TransferServiceConnection(this)
        initializeWidgets()
        restoreState()

        // checking availability of download folders
        if (Pref.useInternal) {
            folderName.text = "${C.INTERNAL_DOWNLOAD_FOLDER.name}/"
        } else {
            val locn = sdCardUri ?: try {
                Uri.parse(Pref.downloadLocation)
            } catch (e: Exception) {
                null
            }
            if (locn != null && !useUriForDownloadLocation(locn)) {
                // If device reboots, access to URI is lost
                // So preferences need to be reverted to use internal storage as default download location
                sdCardUri = null; Pref.useInternal = true
                Pref.downloadLocation = C.INTERNAL_DOWNLOAD_FOLDER.absolutePath
                folderName.text = "${C.INTERNAL_DOWNLOAD_FOLDER.name}/"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val downloadData = Pref.lastAdvancedDownloadData ?: return
        Pref.lastAdvancedDownloadData = null
        url.setText(downloadData.getString(C.dt.url))
        fileName.text = downloadData.optString(C.dt.fileName) ?: ""
        headers.setText(downloadData.optString(C.dt.headers) ?: "")
    }

    private fun showWelcomeMessage() {
        AlertDialog.Builder(this)
            .setTitle("Advanced mode")
            .setMessage(
                """<b>Welcome to HTTP-Downloader's Advanced mode</b><br/>
                |<i>Targeted users: Software developers and Tech geeks</i><br/>
                |All features in interactive download mode (including paid) (and several more) are available for free in Advanced mode.  
                |But if you use this section in the wrong way, your download may get corrupted or failed.<br/>
                |<b>No technical support will be given for any trouble caused to you while using Advanced mode</b><br/>
                |<b>NB:</b> Feel free to report bugs (including exact steps to reproduce it) if you find any<br/><br/>
                |Click on proceed to continue to advanced mode.
            """.trimMargin().asHtml()
            )
            .setPositiveButton("Proceed") { d, _ ->
                d.dismiss()
                Pref.isAdvancedMode1stTime = false
            }
            .setNegativeButton("Go back") { d, _ ->
                d.dismiss()
                finish()
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FOLDER_CHOOSE) {
            if (resultCode == Activity.RESULT_OK)
                data?.data?.also {
                    contentResolver.takePersistableUriPermission(it, data.getRWFlags())
                    useUriForDownloadLocation(it)
                } ?: showSnackBar("Invalid output folder", duration = Snackbar.LENGTH_LONG)
            else showSnackBar("No output folder selected", duration = Snackbar.LENGTH_LONG)
        } else if (requestCode == FILE_CHOOSE) {
            if (resultCode == Activity.RESULT_OK)
                data?.data?.also {
                    enableSplitCheckBox.isChecked = false
                    toggleSplit(View(this))
                    contentResolver.takePersistableUriPermission(it, data.getRWFlags())
                    useUriFileForDownload(it)
                } ?: showSnackBar("Invalid output file", duration = Snackbar.LENGTH_LONG)
            else showSnackBar("No output file selected", duration = Snackbar.LENGTH_LONG)
        }
    }

    private fun useUriFileForDownload(uri: Uri) {
        val file: UriFile? = { UriFile(this, uri.str) } otherwise null
        if (file != null) {
            fileName.text = file.name
            uriFile = file
            file_name.setText(file.name)
            file_name.isEnabled = false
        } else showSnackBar("This file could not be accessed")
    }

    private fun useUriForDownloadLocation(uri: Uri): Boolean {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return false
        if (folder.isFile || folder.name == null) return false
        folderName.text = "${folder.name}/"
        sdCardUri = uri
        Pref.downloadLocation = uri.str
        Pref.useInternal = false
        return true
    }

    private fun initializeWidgets() {
        //need to be enabled only when checkBox is clicked
        parts_count.isEnabled = false; download_method_spinner.isEnabled = false

        offset_text.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) fetchDetails(false)
        }
        offset_text.setOnTextChangedListener {
            areDetailsFetched = false; downloadVisual.setGone()
            val offset = { it.str.trim().toDouble() } otherwise 0.0
            when {
                offset < 10 -> offset_spinner.setSelection(0)
                offset < 1024 -> offset_spinner.setSelection(1)
                else -> offset_spinner.setSelection(3)
            }
        }

        limit_text.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) fetchDetails(false)
        }
        limit_text.setOnTextChangedListener {
            areDetailsFetched = false; downloadVisual.setGone()
            val limit = { it.str.trim().toDouble() } otherwise 0.0
            when {
                limit < 10 -> limit_spinner.setSelection(0)
                limit < 1024 -> limit_spinner.setSelection(1)
                else -> limit_spinner.setSelection(3)
            }
        }
        val textWatcher: (Editable?) -> Unit = {
            areDetailsFetched = false
            downloadVisual.setGone()
        }
        url.setOnTextChangedListener(textWatcher)
        headers.setOnTextChangedListener(textWatcher)

        val adapter1 = object :
            ArrayAdapter<String>(this, R.layout.spinner_item, arrayOf("GB", "MB", "KB", "bytes")) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    setPadding(5.dp, 6.dp, 5.dp, 6.dp)
                }
            }
        }
        offset_spinner.adapter = adapter1
        limit_spinner.adapter = adapter1
        //threads_spinner.adapter = ArrayAdapter(this,R.layout.spinner_item,arrayOf("1","2","3","4","5"))
        download_method_spinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, arrayOf("in parallel", "one after other"))

        val listener: (TextView?, Int?, KeyEvent?) -> Boolean = { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                fetchDetails(false); false
            } else false
        }
        url.setOnEditorActionListener(listener)

        chooseFile.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isProgrammaticCheckChange) changeFile(chooseFile)
        }
        chooseFolder.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isProgrammaticCheckChange) changeFolder(chooseFolder)
        }
    }

    private fun getOptimumPartsCount(): String {
        val response = response ?: return "2"
        val offset = offset_text.valueAsBytes() ?: 0
        val limit = limit_text.valueAsBytes() ?: -1
        val downloadSize = (limit orElse response.total) - offset
        if (downloadSize < 100.KB) return "1" //don't allow making parts if downloadSize<100KB
        return if (downloadSize > MAX_SINGLE_FILE_SIZE)
            ceil(downloadSize / PREFERRED_SINGLE_FILE_SIZE).roundToInt().str
        else "2"
    }

    private var areDetailsFetched = false
    private var response: DownloadObject.ResponseData? = null
    private fun fetchDetails(warnOnError: Boolean = true) {
        val obj = generateTempDownloadObj()
        val offset = offset_text.valueAsBytes() ?: 0
        val limit = limit_text.valueAsBytes() ?: -1
        if (limit != -1L && limit <= offset) {
            showSnackBar(
                "<b>limit</b> should not be less than <b>offset</b><br/>" +
                        "If you start download, value of <b>limit</b> will be ignored"
            )
        }
        obj.onFetchSuccess = { _, response ->
            handler.post {
                this.response = response
                with(response ?: return@post) {
                    if (chooseFolder.isChecked) file_name.changeText(fileName)
                    show_headers.unHide()
                    download_size.unHide()
                    download_size.text = "File size: ${formatSize(total, 3, " ")}"

                    text1.text = formatSize(offset)
                    text2.text = formatSize(limit orElse total)
                    val w0 = offset * 1f / total
                    val w2 = (total - (limit orElse total)) * 1f / total
                    val w1 = 1 - w0 - w2
                    view0.layoutParams =
                        (view0.layoutParams as LinearLayout.LayoutParams).apply { weight = w0 }
                    view1.layoutParams =
                        (view1.layoutParams as LinearLayout.LayoutParams).apply { weight = w1 }
                    view2.layoutParams =
                        (view2.layoutParams as LinearLayout.LayoutParams).apply { weight = w2 }
                    downloadVisual.unHide()
                    val toDownload = (limit orElse total) - offset
                    if (toDownload > MAX_SINGLE_FILE_SIZE) {
                        parts_count.setText(getOptimumPartsCount())
                        enableSplitCheckBox.isChecked = true
                        toggleSplit(enableSplitCheckBox)
                    }
                }

                download_button_progress.hide()
                download_button.unHide()
                areDetailsFetched = true
            }
        }
        obj.onFetchFailed = { _, e ->
            response = null; areDetailsFetched = false
            handler.post {
                download_button_progress.hide()
                download_button.unHide()
                if (warnOnError) showSnackBar(getExceptionCause(e, "fetch_failed"))
            }
        }
        obj.onFetchBegin = {
            handler.post {
                offset_text.changeText(offset_text.valueAsBytes()?.str ?: "", 3)
                limit_text.changeText(limit_text.valueAsBytes()?.str ?: "", 3)
                download_button_progress.unHide()
                download_button.hide()
            }
        }
        obj.startFetchingDetails()
    }

    private var oldFileText = ""
    private var oldOffset = "" to 0
    private var oldLimit = "" to 0
    private fun EditText.changeText(text: String, unit: Int = 0) {
        fun setOld(value: Pair<String, Int>) {
            if (id == R.id.offset_text) oldOffset = value
            else oldLimit = value
        }

        if (this.id == R.id.file_name) {
            if (this@AdvancedDownload.file_name.text.str == text) return
            oldFileText = this@AdvancedDownload.file_name.text.str
            this@AdvancedDownload.file_name.setText(text)
            this@AdvancedDownload.undoFileName.unHide()
            return
        }

        val editText = if (this.id == R.id.offset_text)
            this@AdvancedDownload.offset_text
        else this@AdvancedDownload.limit_text
        val spinner = if (this.id == R.id.offset_text)
            this@AdvancedDownload.offset_spinner
        else this@AdvancedDownload.limit_spinner
        val old = editText.text.str to spinner.selectedItemPosition
        if (old == (text to unit)) return

        setOld(old)
        editText.setText(text); spinner.setSelection(unit)

        val undoButton = if (id == R.id.offset_text)
            this@AdvancedDownload.undoOffset
        else this@AdvancedDownload.undoLimit
        undoButton.unHide()
    }

    private fun EditText.valueAsBytes(): Long? {
        val editText = if (this.id == R.id.offset_text)
            this@AdvancedDownload.offset_text
        else this@AdvancedDownload.limit_text
        val spinner = if (this.id == R.id.offset_text)
            this@AdvancedDownload.offset_spinner
        else this@AdvancedDownload.limit_spinner
        var a = try {
            editText.text.str.trim().toDouble()
        } catch (e: NumberFormatException) {
            return null
        }
        for (i in spinner.selectedItemPosition..2) a *= 1024
        return if (a > Long.MAX_VALUE) null else a.toLong()
    }

    private fun generateTempDownloadObj(): DownloadObject {
        val obj = JSONObject()
        obj.put(C.dt.url, url.text)
        obj.put(C.dt.type, "DownloadObject")
        obj.put(C.dt.id, 0)
        obj.put(C.dt.fileName, file_name.text.str)
        obj.put(C.dt.offset, offset_text.valueAsBytes() ?: 0)
        obj.put(C.dt.limit, limit_text.valueAsBytes() ?: -1)
        obj.put(C.dt.emptyFirstMode, emptyFile.isChecked)
        obj.put(C.dt.headers, headers.text.str)
        return DownloadObject(obj)
    }

    fun startBrowser(v: View) {
        Browser.start(
            context = this,
            from = Browser.Companion.FromOptions.ADVANCED,
            request = C.misc.saveForAdvancedMode
        )
        finish()
    }

    fun changeFolder(v: View) {
        chooseFolder.select {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), FOLDER_CHOOSE)
            } catch (e: Exception) {
                showSnackBar("Can't launch file picker", duration = Snackbar.LENGTH_SHORT)
            }
        }
    }

    fun changeFile(view: View) {
        chooseFile.select {
            with(Intent(Intent.ACTION_CREATE_DOCUMENT)) {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                startActivityForResult(this, FILE_CHOOSE)
            }
        }
    }

    fun showHeaders(v: View) {
        val response = this.response ?: return
        val requestBuilder = StringBuilder()
        for (item in response.requestHeaders)
            requestBuilder.append("<b>${item.key}</b>: ${item.value}<br>")
        val responseBuilder = StringBuilder()
        for (item in response.responseHeaders)
            responseBuilder.append("<b>${item.key}</b>: ${item.value}<br>")
        val headers = "<b><u>REQUEST</u></b><br>$requestBuilder<br>" +
                "<b><u>RESPONSE</u></b><br>$responseBuilder"
        with(AlertDialog.Builder(this)) {
            setTitle("HTTP Headers")
            setMessage(headers.asHtml())
            setCancelable(true)
            setPositiveButton("OK") { d, _ -> d.dismiss() }
            show()
        }
    }

    fun clear(v: View) {
        AlertDialog.Builder(this)
            .setTitle("Clear all fields?")
            .setMessage(("Click OK to reset all text fields").asHtml())
            .setPositiveButton("OK") { d, _ -> clearFields(); d.dismiss() }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private val fieldsData = mutableMapOf<String, Any>()
    private fun restoreFields() {
        url.text = fieldsData["url.text"] as Editable?
        file_name.text = fieldsData["file_name.text"] as Editable?
        show_headers.visibility = fieldsData["show_headers.visibility"] as Int
        download_size.visibility = fieldsData["download_size.visibility"] as Int
        offset_text.text = fieldsData["offset_text.text"] as Editable?
        limit_text.text = fieldsData["limit_text.text"] as Editable?
        headers.text = fieldsData["headers.text"] as Editable?
        folderName.text = fieldsData["folderName.text"] as CharSequence?
        fileName.text = fieldsData["fileName.text"] as CharSequence?
        parts_count.text = fieldsData["parts_count.text"] as Editable?
        emptyFile.isChecked = fieldsData["emptyFile.isChecked"] as Boolean
        undoLimit.visibility = fieldsData["undoLimit.visibility"] as Int
        undoFileName.visibility = fieldsData["undoFileName.visibility"] as Int
        undoOffset.visibility = fieldsData["undoOffset.visibility"] as Int
        downloadVisual.visibility = fieldsData["downloadVisual.visibility"] as Int

        download_method_spinner.setSelection(fieldsData["download_method_spinner.selectedItemPosition"] as Int)
        offset_spinner.setSelection(fieldsData["offset_spinner.selectedItemPosition"] as Int)
        limit_spinner.setSelection(fieldsData["limit_spinner.selectedItemPosition"] as Int)

        enableSplitCheckBox.isChecked = fieldsData["enableSplitCheckBox"] as Boolean
        toggleSplit(View(this))

        areDetailsFetched = fieldsData["areDetailsFetched"] as Boolean
        if (fieldsData.containsKey("response"))
            response = fieldsData["response"] as DownloadObject.ResponseData?
        if (fieldsData.containsKey("sdCardUri"))
            sdCardUri = fieldsData["sdCardUri"] as Uri?
        if (fieldsData.containsKey("uriFile"))
            uriFile = fieldsData["uriFile"] as UriFile?
        fieldsData.clear()
    }

    private fun clearFields() {
        fieldsData["enableSplitCheckBox"] = enableSplitCheckBox.isChecked
        fieldsData["download_method_spinner.selectedItemPosition"] =
            download_method_spinner.selectedItemPosition
        fieldsData["parts_count.text"] = parts_count.text
        fieldsData["emptyFile.isChecked"] = emptyFile.isChecked
        fieldsData["undoLimit.visibility"] = undoLimit.visibility
        fieldsData["undoFileName.visibility"] = undoFileName.visibility
        fieldsData["undoOffset.visibility"] = undoOffset.visibility
        fieldsData["downloadVisual.visibility"] = downloadVisual.visibility
        fieldsData["url.text"] = url.text
        fieldsData["file_name.text"] = file_name.text
        fieldsData["show_headers.visibility"] = show_headers.visibility
        fieldsData["download_size.visibility"] = download_size.visibility
        fieldsData["offset_spinner.selectedItemPosition"] = offset_spinner.selectedItemPosition
        fieldsData["limit_spinner.selectedItemPosition"] = limit_spinner.selectedItemPosition
        fieldsData["headers.text"] = headers.text
        fieldsData["offset_text.text"] = offset_text.text
        fieldsData["limit_text.text"] = limit_text.text
        response?.also { fieldsData["response"] = it }
        sdCardUri?.also { fieldsData["sdCardUri"] = it }
        uriFile?.also { fieldsData["uriFile"] = it }
        fieldsData["areDetailsFetched"] = areDetailsFetched
        fieldsData["folderName.text"] = folderName.text
        fieldsData["fileName.text"] = fileName.text
        log("AdvanedDownload.clear", fieldsData)


        enableSplitCheckBox.isChecked = false
        toggleSplit(View(this))
        download_method_spinner.setSelection(0)
        parts_count.setText("2")
        emptyFile.isChecked = true
        undoLimit.setGone()
        undoFileName.setGone()
        undoOffset.setGone()
        downloadVisual.setGone()
        url.setText("")
        file_name.setText("")
        show_headers.hide()
        download_size.hide()
        headers.setText("")
        offset_text.setText("")
        limit_text.setText("")
        offset_spinner.setSelection(0)
        limit_spinner.setSelection(0)

        areDetailsFetched = false
        response = null
        sdCardUri = null
        uriFile = null
        folderName.text = "(Nothing selected)"
        fileName.text = "(Nothing selected)"

        showSnackBar("All fields were cleared", "undo", Snackbar.LENGTH_LONG) {
            restoreFields()
        }
    }

    fun proceed(v: View) {
        file_name.setText(file_name.text.str.toValidFileName())
        if (!areDetailsFetched || response == null) {
            fetchDetails()
        } else if (chooseFolder.isChecked &&
            duplicateFileExistWithName(file_name.text.str)
        ) {
            showSnackBar("The requested file name already exists", "rename", Snackbar.LENGTH_LONG) {
                var name = renameDuplicateFile(file_name.text.str)
                while (duplicateFileExistWithName(name))
                    name = renameDuplicateFile(name)
                file_name.setText(name)
            }
        } else if (chooseFile.isChecked && uriFile == null) {
            showSnackBar("Choose an output file to start download", "choose") { changeFile(v) }
        } else {
            v.isEnabled = false
            val response = response ?: return
            val partsCount = when {
                chooseFile.isChecked -> 1
                enableSplitCheckBox.isChecked -> try {
                    parts_count.text.str.toInt()
                } catch (e: Exception) {
                    1
                }
                else -> 1
            }
            val downloadSize = (response.limit orElse response.total) - response.offset
            val ids = LongArray(partsCount) { getUniqueId() }
            log(
                "AdvancedDownload", "proceed",
                partsCount, downloadSize,
                response.offset, response.limit,
                ids.contentToString()
            )

            var offset = response.offset
            var limit = offset + downloadSize / partsCount + downloadSize % partsCount
            for (i in 0 until partsCount) {
                val obj = JSONObject().apply {
                    put(C.dt.type, DownloadObject.TYPE)
                    put(C.dt.id, ids[partsCount - i - 1]) //Downloads need to be ordered in reverse

                    if (download_method_spinner.selectedItemPosition == 1) {
                        if (i != 0)
                            put(C.dt.startAfter, "ending ${ids[partsCount - i]}")
                        if (i != partsCount - 1)
                            put(C.dt.onEndAction, "start ${ids[partsCount - i - 2]}")
                    }
                    if (chooseFile.isChecked)
                        put(C.dt.outputFolder, "${C.type.fileUri}:${uriFile!!.uri}")
                    else sdCardUri?.also { put(C.dt.outputFolder, "${C.type.uri}:$it") }

                    put(C.dt.url, response.conn.url)
                    put(C.dt.offset, offset)
                    if (response.limit != -1L || partsCount != 1)
                        put(C.dt.limit, limit)
                    put(C.dt.headers, response.requestHeadersString)
                    put(C.dt.emptyFirstMode, emptyFile.isChecked)
                    val name = when {
                        chooseFile.isChecked -> uriFile!!.name
                        partsCount == 1 -> file_name.text
                        else -> addPartNumTo(file_name.text.str, i + 1)
                    }
                    put(C.dt.fileName, name)
                }

                obj.put(C.dt.isCollapsed, false)
                val intent = Intent(C.filter.START_DOWNLOAD)
                intent.putExtra(C.misc.downloadDataJSON, obj.str)
                transferServiceConnection.request(intent)

                offset = limit; limit += downloadSize / partsCount
            }

            log("AdvancedDownload", "proceed: started download")
            AnimFactory().animateBtn(download_button, R.drawable.next)
            showSnackBar(
                "${if (partsCount == 1) "Download" else "Downloads"} started",
                duration = Snackbar.LENGTH_SHORT,
                btnTxt = "view"
            ) {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
                finish()
            }
            v.isEnabled = true
        }
    }

    private fun String.toValidFileName(): String {
        return this.replace('/', '_')
            .replace('\\', '_')
            .replace('>', '_')
            .replace('<', '_')
            .replace('|', '_')
            .replace('*', '_')
            .replace('"', '_')
            .replace('?', '_')
            .replace(':', '_')
            .replace('%', '_')
    }

    private fun duplicateFileExistWithName(fileName: String): Boolean {
        fun String.matchesTo(nameNExtension: Pair<String, String?>): Boolean {
            val (name, ext) = nameNExtension
            return if (ext == null)
                this.matches(Regex("$name \\(part\\d+\\)$", RegexOption.IGNORE_CASE))
            else this.matches(Regex("$name \\(part\\d+\\)\\.$ext", RegexOption.IGNORE_CASE))
        }

        val sdcardUri = sdCardUri
        return if (enableSplitCheckBox.isChecked) {
            if (sdcardUri == null) {
                C.INTERNAL_DOWNLOAD_FOLDER.listFiles()?.any {
                    it.name.matchesTo(fileName.toNameNExtension())
                } == true
            } else {
                DocumentFile.fromTreeUri(this, sdcardUri)?.listFiles()?.any {
                    it.name?.matchesTo(fileName.toNameNExtension()) == true
                } == true
            }
        } else {
            if (sdcardUri == null) File(C.INTERNAL_DOWNLOAD_FOLDER, fileName).exists()
            else DocumentFile.fromTreeUri(this, sdcardUri)?.findFile(fileName) != null
        }
    }

    override fun onBackPressed() {
        if (snackBar == null) {
            startActivity(Intent(this, MainActivity::class.java))
            super.onBackPressed()
        } else dismissSnackBar()
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    fun headerInfo(v: View) {
        showInfo(
            title = "HTTP Headers",
            msg = "You can customize HTTP Headers sent while requesting download.<br/>" +
                    "All header parameters added here will be put to request headers<br/>" +
                    "<b>Note:</b> Don't specify <i>Content-Range</i> here. Use offset & limit fields instead"
        )
    }

    fun offsetInfo(v: View) {
        showInfo(
            title = "Offset",
            msg = "The byte position (inclusive) from where download will begin. <br/>" +
                    "<b>Eg:</b> A download with <b>offset = <i>10bytes</i></b> and <b>limit = <i>20bytes</i></b> " +
                    "will have 10 bytes (from byte position 10 to byte position 19)"
        )
    }

    fun limitInfo(v: View) {
        showInfo(
            title = "Limit",
            msg = "The byte position (exclusive) up to which download will be requested<br/>" +
                    "<b>Eg:</b> A download with <b>offset = <i>10bytes</i></b> and <b>limit = <i>20bytes</i></b> " +
                    "will have 10 bytes (from byte position 10 to byte position 19)"
        )
    }

    fun splitSizeInfo(v: View) {
        showInfo(
            title = "Auto-Splitting",
            msg = "With this feature HTTP Downloader will download your files a several parts automatically.<br/>" +
                    "Choose the mode which best suits your need<br/><br/>" +
                    "<b>In parallel:</> Use this feature to create multiple parallel connections" +
                    " to speed up downloads from servers which limit speed per connection<br/>" +
                    "<br/>" +
                    "<b>One after other:</b> Some devices/SDCards do not support files of size more than 4GB." +
                    "Use this feature to download huge (4GB+) files as several small parts. <br/>" +
                    "This is better alternative as <b>in parallel</b> mode will drain your battery faster"
        )
    }

    fun emptyFileInfo(v: View) {
        showInfo(
            title = "Create empty file",
            msg = "If this option is ticked, HTTP-Downloader will allocate a file of size equal to that of your download." +
                    "This helps you avoid download interruptions due to lack of storage space."
        )
    }
    fun threadInfo(v: View) {}

    private fun showInfo(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg.asHtml())
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private var isFileNameUndone = false
    fun undoFileName(v: View) {
        if (isFileNameUndone) animFactory.animateBtn(v as ImageView, R.drawable.undo)
        else animFactory.animateBtn(v as ImageView, R.drawable.redo)
        isFileNameUndone = !isFileNameUndone

        file_name.changeText(oldFileText)
    }

    private var isOffsetUndone = false
    fun undoOffset(v: View) {
        if (isOffsetUndone) animFactory.animateBtn(v as ImageView, R.drawable.undo)
        else animFactory.animateBtn(v as ImageView, R.drawable.redo)
        isOffsetUndone = !isOffsetUndone

        offset_text.changeText(oldOffset.first, oldOffset.second)
    }

    private var isLimitUndone = false
    fun undoLimit(v: View) {
        if (isLimitUndone) animFactory.animateBtn(v as ImageView, R.drawable.undo)
        else animFactory.animateBtn(v as ImageView, R.drawable.redo)
        isLimitUndone = !isLimitUndone

        limit_text.changeText(oldLimit.first, oldLimit.second)
    }

    fun toggleSplit(view: View) {
        if (chooseFile.isChecked && enableSplitCheckBox.isChecked) {
            showSnackBar(
                "Can't use this option when you have selected file mode for download",
                duration = Snackbar.LENGTH_LONG
            )
            enableSplitCheckBox.isChecked = false
        }
        if (enableSplitCheckBox.isChecked) {
            parts_count.isEnabled = true; download_method_spinner.isEnabled = true
        } else {
            parts_count.isEnabled = false; download_method_spinner.isEnabled = false
        }
    }

    private fun RadioButton.select(onOK: (() -> Unit)? = null) {
        if (Pref.isFileModeFirstTime && id == R.id.chooseFile) {
            Pref.isFileModeFirstTime = false
            AlertDialog.Builder(this@AdvancedDownload)
                .setTitle("File mode")
                .setMessage(
                    """File mode lets you select broader range of download locations like <b><i>cloud drives</i></b><br/>
                        |Also note that if the selected file is not empty, download will be requested from end of the file & will be appended to it. 
                        |In effect, you can use this to resume failed downloads. 
                        |<br/>
                        |You cannot use <b>Auto-Split</b> option in this mode
                    """.trimMargin().asHtml()
                )
                .setPositiveButton("OK") { d, _ -> d.dismiss(); onOK?.invoke() }
                .setCancelable(false)
                .show()
        } else onOK?.invoke()

        val disabled = if (id == R.id.chooseFile)
            this@AdvancedDownload.chooseFolder
        else this@AdvancedDownload.chooseFile
        val (bold, normal) = if (this.id == R.id.chooseFile)
            this@AdvancedDownload.fileName to this@AdvancedDownload.folderName
        else this@AdvancedDownload.folderName to this@AdvancedDownload.fileName

        isProgrammaticCheckChange = true
        disabled.isChecked = false
        this.isChecked = true
        isProgrammaticCheckChange = false

        normal.typeface = Typeface.DEFAULT
        normal.setTextColor(Color.parseColor("#555555"))
        bold.typeface = Typeface.DEFAULT_BOLD
        bold.setTextColor(Color.parseColor("#000000"))

        if (id == R.id.chooseFile) {
            this@AdvancedDownload.file_name.isEnabled = false
            uriFile?.also { this@AdvancedDownload.file_name.setText(it.name) }
        } else this@AdvancedDownload.file_name.isEnabled = true
    }

    private fun saveState() {
        val data = JSONObject()
        data.put("enableSplitCheckBox", enableSplitCheckBox.isChecked)
        data.put(
            "download_method_spinner.selectedItemPosition",
            download_method_spinner.selectedItemPosition
        )
        data.put("parts_count.text", parts_count.text)
        data.put("emptyFile.isChecked", emptyFile.isChecked)
        data.put("undoLimit.visibility", undoLimit.visibility)
        data.put("undoFileName.visibility", undoFileName.visibility)
        data.put("undoOffset.visibility", undoOffset.visibility)
        data.put("url.text", url.text)
        data.put("file_name.text", file_name.text)
        data.put("show_headers.visibility", show_headers.visibility)
        data.put("download_size.visibility", download_size.visibility)
        data.put("offset_spinner.selectedItemPosition", offset_spinner.selectedItemPosition)
        data.put("limit_spinner.selectedItemPosition", limit_spinner.selectedItemPosition)
        data.put("headers.text", headers.text)
        data.put("offset_text.text", offset_text.text)
        data.put("limit_text.text", limit_text.text)
        sdCardUri?.also { data.put("sdCardUri", it.str) }
        uriFile?.also { data.put("uriFile", it.uri.str) }
        data.put("folderName.text", folderName.text)
        data.put("fileName.text", fileName.text)
        Pref.lastAdvancedData = data
    }

    private fun restoreState() {
        val data = Pref.lastAdvancedData ?: return
        url.setText(data.getString("url.text"))
        file_name.setText(data.getString("file_name.text"))
        show_headers.visibility = data.getInt("show_headers.visibility")
        download_size.visibility = data.getInt("download_size.visibility")
        offset_text.setText(data.getString("offset_text.text"))
        limit_text.setText(data.getString("limit_text.text"))
        headers.setText(data.getString("headers.text"))
        folderName.text = data.getString("folderName.text")
        fileName.text = data.getString("fileName.text")
        parts_count.setText(data.getString("parts_count.text"))
        emptyFile.isChecked = data.getBoolean("emptyFile.isChecked")
        undoLimit.visibility = data.getInt("undoLimit.visibility")
        undoFileName.visibility = data.getInt("undoFileName.visibility")
        undoOffset.visibility = data.getInt("undoOffset.visibility")

        download_method_spinner.setSelection(data.getInt("download_method_spinner.selectedItemPosition"))
        offset_spinner.setSelection(data.getInt("offset_spinner.selectedItemPosition"))
        limit_spinner.setSelection(data.getInt("limit_spinner.selectedItemPosition"))

        enableSplitCheckBox.isChecked = data.getBoolean("enableSplitCheckBox")
        toggleSplit(View(this))

        if (data.has("sdCardUri"))
            sdCardUri = { Uri.parse(data.getString("sdCardUri")) } otherwise null
        if (data.has("uriFile"))
            uriFile = { UriFile(this, data.getString("uriFile")) } otherwise null
    }
}
