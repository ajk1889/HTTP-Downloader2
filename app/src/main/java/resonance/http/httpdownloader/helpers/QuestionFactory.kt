package resonance.http.httpdownloader.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_simple_mode.*
import org.json.JSONException
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.*
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.mainOptions.*
import java.io.File
import kotlin.collections.set

class QuestionFactory(val activity: SimpleActivity) {
    companion object {
        const val FILE_SELECT = "fileName"
        private const val BROWSER_RESUME = "browserResume"
        const val FOLDER_SELECT = "selectFolder"
        private const val SHARED_DATA_QR = 130
        private const val QR_CODE_SCAN_TRIGGER = "qrCodeScanTrigger"
        val tryAgain get() = "dHJ5IGFnYWluLiA="
    }

    internal val parent: LinearLayout = activity.questions_container
    private val onResume = activity.onResumeTriggers
    internal val onActivityResult = activity.onActivityResultTriggers

    val tempStorage = mutableMapOf<String, Any>()
    var reachedQuestions = mutableListOf<String>()
    var isForwardButtonClick = false

    internal lateinit var question0: Question
    fun question0(): Question = Question(
        activity = activity,
        name = "question",
        _description = null,
        _question = "<b>Hi there. How can HTTP-Downloader help you today?</b>",
        onInit = null,
        onGoBack = {
            activity.startActivity(Intent(activity, MainActivity::class.java))
            activity.finish()
            false
        },
        onGoForward = {
            when {
                reachedQuestions.contains("safelyDownloadAFile") -> {
                    it.optionAt(0).click()
                    reachedQuestions.contains("downloadDetails")
                }
                reachedQuestions.contains("resumeFailedDownload") -> {
                    it.optionAt(1).click()
                    reachedQuestions.contains("failedFromWhere")
                }
                reachedQuestions.contains("multiDeviceDownload") -> {
                    it.optionAt(2).click()
                    reachedQuestions.contains("whichPart")
                }
                reachedQuestions.contains("filePartDownload") -> {
                    it.optionAt(3).click()
                    reachedQuestions.contains("whichRange")
                }
                reachedQuestions.contains("multiPartDownload") -> {
                    it.optionAt(4).click()
                    reachedQuestions.contains("howManyParts")
                }
                else -> false
            }
        }
    ).apply {
        val factory = this@QuestionFactory
        addOption(SafelyDownloadAFile(factory).make())
        addOption(ResumeFailedDownload(factory).make())
        addOption(MultiDeviceDownload(factory).make())
        addOption(FilePartDownload(factory).make())
        addOption(MultiPartDownload(factory).make())
        addOption(option6())
        addOption(option7())
        question0 = this
    }

    private fun Option.click() {
        activity.questionFactory.isForwardButtonClick = true
        view.findViewById<View>(R.id.text).callOnClick()
        activity.questionFactory.isForwardButtonClick = false
    }

    private lateinit var option6: Option
    private fun option6() = Option(
        activity = activity,
        option = "File parts joiner",
        short = "fileJoiner",
        onInit = null,
        onClick = {
            val intent = Intent(activity, FileJoiner::class.java)
            intent.putExtra(C.misc.from, "SimpleActivity")
            activity.startActivity(intent)
        },
        useCustomView = false
    ).apply { option6 = this }

    private lateinit var option7: Option
    private fun option7() = Option(
        activity = activity,
        option = "App settings",
        short = "settings",
        onInit = null,
        onClick = {
            SettingsActivity.start(activity, SimpleActivity::class.java.name)
        },
        useCustomView = false
    ).apply { option7 = this }

    /**
     * @param nextQuestion : a lambda function that wraps the question
     * This avoids need of initializing question at time of function call.
     * Question can be initialized on demand during onResumeTrigger
     *
     * This function clears {@see Pref.key#lastBrowserDownloadData}
     */
    internal fun clickToOpenBrowser(
        option: String = "Click here to open browser",
        nextQuestion: () -> Question
    ): Option {
        //removing previously stored lastDownload data
        Pref.lastBrowserDownloadData = null

        // to avoid snackBar.onDismiss removing onResumeTrigger
        // when browser was launched by clicking option while snackBar is visible
        var isOptionClicked: Boolean

        return Option(
            activity = activity,
            option = option,
            short = "openBrowser",
            onInit = null,
            onClick = {
                isOptionClicked = true
                it.isEnabled = false
                openBrowser(activity.requestUrl)
                onResume[BROWSER_RESUME] = {
                    if (Pref.key.lastBrowserDownloadData.exists()) {
                        nextQuestion().initialize().addTo(parent)
                        onResume.remove(BROWSER_RESUME)
                    } else {
                        it.isEnabled = true
                        showSnackBar(
                            msg = "No download was detected.<br>Open browser and <b>try again</b>",
                            btnTxt = "Open",
                            duration = Snackbar.LENGTH_LONG,
                            onDismiss = { if (!isOptionClicked) onResume.remove(BROWSER_RESUME) },
                            onClick = { openBrowser(activity.requestUrl); it.isEnabled = false }
                        )
                    }
                    isOptionClicked = false
                    null
                }
            },
            useCustomView = false
        )
    }

    @SuppressLint("InflateParams")
    internal fun fromShared(
        optionText: String = "Use shared download data",
        nextQuestion: () -> Question
    ): Option = Option(
        activity = activity,
        option = optionText,
        short = "fromShared",
        onInit = null,
        onClick = { option ->
            val view = activity.layoutInflater.inflate(R.layout.dialog_edit_text_multiline, null)
            val editText = view.findViewById<EditText>(R.id.file_name)
            editText.gravity = Gravity.START or Gravity.TOP
            editText.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150.dp
            ).apply { marginStart = 10.dp; marginEnd = 10.dp; topMargin = 10.dp }
            editText.hint = "Copy-paste data here"

            activity.showKeyboard()

            val dialog = AlertDialog.Builder(activity)
                .setTitle("Download data")
                .setView(view)
                .setPositiveButton("Continue") { _, _ -> }
                .setNegativeButton("Cancel") { _, _ -> activity.hideKeyboard() }
                .setNeutralButton("Scan QR") { _, _ ->
                    activity.hideKeyboard()
                    activity.handler.postDelayed({
                        val intent = Intent(activity, QrCodeActivity::class.java)
                        activity.startActivityForResult(intent, SHARED_DATA_QR)
                    }, 300)
                    activity.onActivityResultTriggers[QR_CODE_SCAN_TRIGGER] = { req, result, data ->
                        if (req == SHARED_DATA_QR && result == Activity.RESULT_OK && data != null)
                            data.getStringExtra(C.misc.qrScanResult)?.also {
                                if (processSharedData(it)) {
                                    option.isEnabled = false
                                    option.disabledReason =
                                        "Press back button to enable this option"
                                    nextQuestion().initialize().addTo(parent)
                                }
                            }
                        activity.onActivityResultTriggers.remove(QR_CODE_SCAN_TRIGGER)
                    }
                }
                .setCancelable(false)
                .create()

            dialog.setOnShowListener {
                val color = ContextCompat.getColor(activity, R.color.colorAccent_dark)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color)
            }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = editText.text.str.trim()
                if (text == "") {
                    editText.setText("")
                    editText.hint = "Valid download data required"
                    return@setOnClickListener
                }
                activity.hideKeyboard()
                if (processSharedData(text)) {
                    activity.handler.postDelayed({
                        option.isEnabled = false
                        option.disabledReason = "Press back button to enable this option"
                        nextQuestion().initialize().addTo(parent)
                    }, 200)
                }
                dialog.dismiss()
            }

            editText.postDelayed({
                if (activity.pasteClipBoardTo(editText))
                    dialog.setTitle("Data <i><font color='#3179A6'>(pasted)</font></i>".asHtml())
            }, 200)
        }
    )

    private fun processSharedData(data: String): Boolean {
        try {
            val shared = JSONObject(data)
            val new = JSONObject()
            new.put(C.dt.type, DownloadObject.TYPE)
            new.put(C.misc.from, C.misc.shared)
            new.put(C.dt.url, shared.getString(C.dt.url))
            if (shared.has(C.dt.headers))
                new.put(C.dt.headers, shared.getString(C.dt.headers))
            downloadData = new
            return true
        } catch (e: JSONException) {
            activity.showSnackBar(
                "The download data you have entered is <b>invalid</b>. Please try again"
            )
            return false
        }
    }

    private lateinit var fileNameOption: Option
    @SuppressLint("InflateParams")
    internal fun fileName(
        option: String = "Name",
        fileNameSuffix: String = "",
        mainOption: MainOption
    ): Option {
        downloadData = downloadData.apply { put(C.misc.fileNameSuffix, fileNameSuffix) }
        var name = downloadData.optString(C.dt.fileName, "loading...")
        var isNameEdited = false

        fun Option.setFileName(n: String?, showWarning: Boolean) {
            name = n?.asSanitizedFileName() ?: ""
            if (n == null) {
                text = "$option: <b>(Tap to name file)</b>"
                silently { downloadData = downloadData.apply { remove(C.dt.fileName) } }
            } else {
                val displayName =
                    if (isNameEdited) name else addSuffixToFileName(name, fileNameSuffix)
                text = "$option: <b>${displayName.asSanitizedFileName()}</b>"
                downloadData = downloadData.apply {
                    put(C.dt.fileName, name)
                    if (isNameEdited) remove(C.misc.fileNameSuffix)
                }

                with(getConflictingFile(mainOption)) {
                    if (this != null) handleFileNameConflicts(showWarning, this)
                    else removeWarningIcon()
                }
            }
        }
        return Option(
            activity = activity,
            option = "$option: loading...",
            short = "fileName",
            onInit = { op ->
                if (name == "loading...") {
                    op.isEnabled = false
                    op.disabledReason = "Please wait until file name is fetched"
                    mainOption.runWithResponse(
                        data = downloadData,
                        onSuccess = { response ->
                            op.isEnabled = true
                            op.setFileName(response.fileName, false)
                        },
                        onError = {
                            op.isEnabled = true
                            op.setFileName(null, false)
                        }
                    )
                } else {
                    op.isEnabled = true
                    op.setFileName(name, false)
                }
            },
            onClick = {
                val view =
                    activity.layoutInflater.inflate(R.layout.dialog_edit_text_multiline, null)
                val editText = view.findViewById<EditText>(R.id.file_name)
                editText.inputType = InputType.TYPE_CLASS_TEXT

                var text = if (isNameEdited) name else addSuffixToFileName(name, fileNameSuffix)
                editText.setText(text.asSanitizedFileName())
                editText.selectFileName()
                activity.showKeyboard()

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Change file name")
                    .setView(view)
                    .setPositiveButton("Change") { _, _ -> } // onClick will be explicitly handled to avoid automatic dismiss
                    .setNegativeButton("Cancel") { d, _ -> activity.hideKeyboard(); d.dismiss() }
                    .setNeutralButton("Undo") { _, _ -> } // onClick will be explicitly handled to avoid automatic dismiss
                    .setCancelable(false)
                    .create()
                dialog.setOnShowListener {
                    val color = ContextCompat.getColor(activity, R.color.colorAccent_dark)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color)
                }
                dialog.show()

                // Explicitly specifying action to avoid automatic dismiss of alert dialog
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    text = if (isNameEdited) name else addSuffixToFileName(name, fileNameSuffix)
                    editText.setText(text.asSanitizedFileName())
                    editText.selectFileName()
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
                    val newName = editText.text.str.trim()
                    if (newName.isEmpty()) {
                        editText.setText("")
                        editText.hint = "File name required"
                    } else {
                        isNameEdited = true
                        it.setFileName(editText.text.str, true)
                        activity.hideKeyboard(); dialog.dismiss()
                    }
                }
            },
            useCustomView = false
        ).also { fileNameOption = it }
    }

    private lateinit var selectFolder: Option
    internal fun selectFolder(
        prefix: String = "Download folder",
        mainOption: MainOption
    ) = Option(
        activity = activity,
        option = "$prefix: <b>....</b>",
        short = "folderName",
        onInit = {
            val defaultFolderName = getDefaultDownloadFolderName(activity)
            if (defaultFolderName != null) {
                downloadData = downloadData.apply { put(C.dt.outputFolder, C.defaultOutputFolder) }
                it.text = "$prefix: <b>$defaultFolderName</b>"
            } else {
                downloadData = downloadData.apply { remove(C.dt.outputFolder) }
                it.text = "$prefix: <b>(Tap to choose)</b>"
            }
        },
        onClick = {
            onActivityResult[FOLDER_SELECT] = { requestCode: Int, resultCode: Int, data: Intent? ->
                if (requestCode == 250 && resultCode == Activity.RESULT_OK) {
                    data?.data?.also { uri ->
                        activity.contentResolver.takePersistableUriPermission(
                            uri,
                            data.getRWFlags()
                        )
                        if (DocumentFile.fromTreeUri(activity, uri)?.isDirectory != true) {
                            activity.showSnackBar("The selected folder is invalid")
                            return@also
                        }
                        val uriFolderName: String? = uri.getDisplayName(activity)
                        downloadData = downloadData.apply { put(C.dt.outputFolder, "${C.type.uri}:$uri") }
                        Pref.useInternal = false
                        Pref.downloadLocation = uri.str
                        if (uriFolderName != null)
                            it.text = "$prefix: <b>$uriFolderName</b>"
                        else it.text = "$prefix: <b>ERROR</b>"

                        with(getConflictingFile(mainOption)) {
                            if (this != null) handleFileNameConflicts(true, this)
                            else removeWarningIcon()
                        }
                    }
                } else {
                    showSnackBar(
                        "Can't find your required folder?\n" +
                                "See troubleshooting steps", "view", Snackbar.LENGTH_LONG
                    ) {
                        val i = Intent(Intent.ACTION_VIEW)
                        i.data = Uri.parse(
                            "https://resonance00x0.github.io/http-downloader/" +
                                    "faq#cant-locate-files-and-folders-in-file-picker"
                        )
                        try {
                            activity.startActivity(i)
                        } catch (e: Exception) {
                            showSnackBar(
                                "Please install a browser and try again",
                                duration = Snackbar.LENGTH_SHORT
                            )
                        }
                    }
                }; onActivityResult.remove(FOLDER_SELECT)
            }
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 250)
            } catch (e: Exception) {
                showSnackBar("Can't launch file picker", duration = Snackbar.LENGTH_SHORT)
            }
        },
        useCustomView = false
    ).also { selectFolder = it }

    internal fun totalSize(prefix: String = "Size", mainOption: MainOption): Option = Option(
        activity = activity,
        option = "$prefix: <b>loading...</b>",
        short = "totalSize",
        onInit = { option ->
            option.isEnabled = false
            option.disabledReason = "You can't change download's total file size." +
                    "<br>If you want to download a file part, go back and choose <b><u>Download a file part</u></b> option"
            mainOption.runWithResponse(
                data = downloadData,
                onSuccess = { response ->
                    option.text = "$prefix: <b>${formatSize(response.total, 3, " ")}</b>"
                },
                onError = {
                    option.text = "$prefix: <b><font color='#f2a983'>ERROR</font></b>"
                    option.disabledReason = getExceptionCause(it, "QuestionFactory")
                }
            )
        },
        onClick = null,
        useCustomView = false
    )

    private var shouldAppendFile = false
    private var allow4GbFile = false
    private var ignoreLowStorage = false

    private lateinit var startDownload: Option
    @SuppressLint("SetTextI18n", "InflateParams")
    internal fun startDownload(
        mainOption: MainOption,
        sendDownloadRequest: ((data: JSONObject) -> Unit)? = null
    ) = Option(
        activity = activity,
        option = "<u><b>Start download</b></u>",
        short = "start",
        onInit = null,
        onClick = {
            val data = downloadData
            val conflictingName = getConflictingFile(mainOption)
            val remainingSpace = getRemainingSpace(data.optString(C.dt.outputFolder, ""))
            val downloadSize = mainOption.response?.downloadSize() ?: 0L

            if (!data.has(C.dt.url)) {
                showSnackBar("<font color='red'>No URL specified.</font> Please click on <b><u>URL</u></b> to go to browser and click on your download link")
                return@Option
            } else if (!data.has(C.dt.fileName)) {
                showSnackBar("<font color='red'>No file name specified.</font> Please click on <b><u>${fileNameOption.text}</u></b> to name the file")
                return@Option
            } else if (!data.has(C.dt.outputFolder)) {
                showSnackBar("<font color='red'>No folder selected.</font> Please click on <b><u>${selectFolder.text}</u></b> to select download location")
                return@Option
            } else if (!shouldAppendFile && conflictingName != null) {
                AlertDialog.Builder(activity)
                    .setTitle("Duplicate file")
                    .setMessage(
                        """There is already a file with same name ($conflictingName) in the folder.<br>
                                |You can <b>choose a different folder</b> or <b>change file name</b><br>
                                |If you click on <b>proceed</b>, remaining download will be appended to the existing file<br><br>
                                |<b>Tip:</b> If you are not sure of what to do, <font color='#497AFF'><b>Go back and Click on <u>${fileNameOption.text}</u></b></font>""".trimMargin().asHtml()
                    )
                    .setPositiveButton("Append") { d, _ ->
                        shouldAppendFile = true
                        d.dismiss()
                        it.onClick?.invoke(it) //calling this function recursively
                    }
                    .setNegativeButton("Back") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .show()
                return@Option
            } else if (!allow4GbFile && Pref.isFAT32Probable
                && (mainOption.response?.total ?: 0) >= 4.GB && Pref.show4GbWarningAgain
            ) {
                val view = activity.layoutInflater.inflate(R.layout.view_4gb_warning, null)
                view.findViewById<CheckBox>(R.id.checkbox).apply {
                    setOnCheckedChangeListener { _, isChecked ->
                        Pref.show4GbWarningAgain = !isChecked
                    }
                    isChecked = true
                }
                view.findViewById<TextView>(R.id.text).text = """
                    |Your storage may not support files greater than 3.99GB & your download is of 
                    |${formatSize((mainOption.response?.total ?: 0), 2)}<br><br>
                    |If your download fails due to that issue, <b>HTTP-Downloader will download remaining to a new file part</b>
                    """.trimMargin().trim().asHtml()

                AlertDialog.Builder(activity)
                    .setTitle("Big file")
                    .setView(view)
                    .setPositiveButton("OK") { d, _ ->
                        allow4GbFile = true
                        d.dismiss()
                        it.onClick?.invoke(it) //calling this function recursively
                    }
                    .setNegativeButton("Back") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .show()
            } else if (!ignoreLowStorage && downloadSize >= remainingSpace) {
                AlertDialog.Builder(activity)
                    .setTitle("Low storage space")
                    .setMessage(
                        """You do not have enough storage space left on your device to download this file.<br/>
                        |Free up at least <b>${formatSize(
                            downloadSize - remainingSpace,
                            2,
                            " "
                        )}</b> to ensure uninterrupted download<br/>
                        |<br/><b>Tip:</b> Click on <b>continue</b> to proceed anyway
                    """.trimMargin().asHtml()
                    )
                    .setPositiveButton("Continue") { d, _ ->
                        ignoreLowStorage = true
                        d.dismiss()
                        it.onClick?.invoke(it)
                    }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .setCancelable(false)
                    .show()
            } else {
                it.isEnabled = false
                it.disabledReason = "Download already started"
                if(data.has(C.misc.fileNameSuffix) && data.has(C.dt.fileName))
                    data.put(C.dt.fileName, addSuffixToFileName(data.getString(C.dt.fileName),data.getString(C.misc.fileNameSuffix)))

                if (sendDownloadRequest == null) {
                    data.put(C.dt.isCollapsed, false)
                    activity.transferServiceConnection.request(Intent(C.filter.START_DOWNLOAD).apply {
                        putExtra(C.misc.downloadDataJSON, data.str)
                    })
                    activity.apply {
                        questions_container.removeAllViews()
                        questionFactory = QuestionFactory(this)
                        questionFactory.question0().initialize().addTo(questions_container)
                        forward.hide()
                    }
                    showSnackBar("Download started", "View", Snackbar.LENGTH_SHORT) {
                        val intent = Intent(activity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        activity.startActivity(intent)
                        activity.finish()
                    }
                } else sendDownloadRequest(data)
            }
        },
        useCustomView = false
    ).also { startDownload = it }

    @SuppressLint("InflateParams")
    internal fun emptyFirstModeOption(text: String = "Create an empty file before download"): Option =
        Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = activity.layoutInflater.inflate(R.layout.create_empty_file,null)
            it.view.findViewById<CheckBox>(R.id.checkbox).apply {
                setText(text)
                setOnCheckedChangeListener { _, isChecked ->
                    downloadData = downloadData.apply { put(C.dt.emptyFirstMode, isChecked) }
                }
            }
        },
        onClick = null,
        useCustomView = true
    )

    @SuppressLint("InflateParams")
    internal fun counterView(
        from: Int,
        to: Int,
        afterTextChanged: ((EditText) -> Unit)? = null
    ): View {
        return activity.layoutInflater.inflate(R.layout.counter_option, null).apply {
            val countBox = findViewById<EditText>(R.id.count)
            countBox.count = from
            findViewById<ImageView>(R.id.down).setOnClickListener {
                val count = countBox.count
                if (count > from) countBox.count = count - 1
                else countBox.count = to
            }
            findViewById<ImageView>(R.id.up).setOnClickListener {
                val count = countBox.count
                if (count < to) countBox.count = count + 1
                else countBox.count = from
            }
            countBox.nextFocusLeftId = countBox.id
            countBox.nextFocusRightId = countBox.id
            countBox.setOnTextChangedListener {
                if (countBox.count < from) countBox.count = from
                if (countBox.count > to) countBox.count = to
                afterTextChanged?.invoke(countBox)
            }
        }
    }

    private var EditText.count: Int
        get() = { text.str.toInt() } otherwise 2
        set(value) {
            val txt = value.str; setText(txt); setSelection(txt.length)
        }

    internal var downloadData: JSONObject
        get() = Pref.lastBrowserDownloadData ?: throw UnreachablePoint(1)
        set(value) {
            Pref.lastBrowserDownloadData = value
        }

    private fun openBrowser(url: String? = null) {
        Browser.start(
            context = activity,
            from = Browser.Companion.FromOptions.SIMPLE,
            url = url,
            request = C.misc.saveToPreferences
        )
    }

    private fun getDefaultDownloadFolderName(ctx: Context): String? {
        if (Pref.useInternal) {
            return if (C.INTERNAL_DOWNLOAD_FOLDER.exists() || C.INTERNAL_DOWNLOAD_FOLDER.mkdirs())
                C.INTERNAL_DOWNLOAD_FOLDER.name else null
        } else {
            try {
                val uri = Uri.parse(Pref.downloadLocation)
                val file = DocumentFile.fromTreeUri(ctx, uri) ?: return null
                return if (file.isDirectory) file.name else null
            } catch (e: IllegalStateException) {
                Pref.useInternal = true
                Pref.downloadLocation = C.INTERNAL_DOWNLOAD_FOLDER.absolutePath
                return if (C.INTERNAL_DOWNLOAD_FOLDER.exists() || C.INTERNAL_DOWNLOAD_FOLDER.mkdirs())
                    C.INTERNAL_DOWNLOAD_FOLDER.name
                else null
            }
        }
    }

    internal fun showSnackBar(
        msg: String,
        btnTxt: String = "OK",
        duration: Int = Snackbar.LENGTH_INDEFINITE,
        onDismiss: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ) {
        activity.showSnackBar(msg, btnTxt, duration, onDismiss, onClick)
    }

    internal fun reAdd(old: Question, fresh: Question): Boolean {
        parent.removeView(old.layout)
        fresh.initialize().addTo(parent)
        if (old.question != fresh.question || old.description != fresh.description)
            throw RuntimeException("old & fresh questions must be identical for re-adding")
        return true
    }

    private fun handleFileNameConflicts(showDirectly: Boolean, conflictingName: String) {
        val warning = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp)
            setImageResource(R.drawable.warning)
        }
        fileNameOption.view.findViewById<LinearLayout>(R.id.holder).apply {
            if (childCount == 1) addView(warning)
        }
        warning.setOnClickListener {
            activity.showSnackBar(
                """There is already a file ($conflictingName) in the folder.<br>
                |<font color='#FFB545'>choose a different folder</font> 
                |or <font color='#4ED8FF'>change file name</font>""".trimMargin()
            )
        }
        if (showDirectly) warning.callOnClick()
    }

    private fun removeWarningIcon() {
        fileNameOption.view.findViewById<LinearLayout>(R.id.holder).apply {
            val view = getChildAt(0)
            removeAllViews()
            addView(view)
        }
    }

    /**
     * Checks for any pre-existing file, while specifying download's file name.
     * In case of multi-part download, checks whether any of file-part1.ext, file-part2.ext... exists
     * @return name_of_conflicting_file if exists, null otherwise
     */
    private fun getConflictingFile(mainOption: MainOption): String? {
        if (!downloadData.has(C.dt.outputFolder)) return null
        val folderString = downloadData.getString(C.dt.outputFolder)
        val fileName = downloadData.optString(C.dt.fileName, null) ?: return null
        when (mainOption) {
            is MultiPartDownload -> { // need to check all file parts for conflict
                val count = downloadData.optInt(C.dt.partsCount, -1)
                if (count == -1) return null
                for (i in 0 until count) {
                    with(getConflictingFile(folderString, addPartNumTo(fileName, i + 1))) {
                        if (this != null) return this
                    }
                }
                return null
            }
            is MultiDeviceDownload, is FilePartDownload -> { // may contain suffix for file name
                val withSuffix = addSuffixToFileName(
                    fileName,
                    downloadData.optString(C.misc.fileNameSuffix, "")
                )
                return getConflictingFile(folderString, withSuffix)
            }
            else -> return getConflictingFile(
                folderString,
                fileName
            ) // check only selected file name
        }
    }

    /**
     * Checks for any pre-existing file, while specifying download's file name.
     * @param folderString folder to be searched
     * @param fileName expected file name (of which conflict should be checked)
     * @return name_of_conflicting_file if exists, null otherwise
     */
    private fun getConflictingFile(folderString: String, fileName: String): String? {
        when {
            folderString.isUri() -> {
                val folder = DocumentFile.fromTreeUri(activity, folderString.toUri()) ?: return null
                if (!folder.isDirectory)
                    throw RuntimeException("Unreachable point 2: The treeUri points to a file")
                for (file in folder.listFiles())
                    if (file?.name == fileName) return fileName
                return null
            }
            folderString.isFile() -> {
                val folder = folderString.toFolder()
                if (!folder.isDirectory) {
                    if (!folder.renameTo(File("$folder.original")) || !folder.mkdirs())
                        throw RuntimeException("Unreachable point 3: $folder is not a directory")
                }
                for (file in folder.listFiles() ?: return null)
                    if (file.name == fileName) return fileName
                return null
            }
            else -> return null
        }
    }

    private val Int.dp: Int
        get() {
            val metrics = activity.resources.displayMetrics
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                this.toFloat(),
                metrics
            ).toInt()
        }

    private fun EditText.selectFileName() {
        val text = text.str
        if (text.contains('.')) {
            if (text[0] == '.') selectAll()
            else setSelection(0, text.lastIndexOf('.'))
        } else selectAll()
    }

    @SuppressLint("UsableSpace")
    private fun getRemainingSpace(opFolder: String): Long {
        try {
            if (opFolder.isUri()) {
                val tree = DocumentFile.fromTreeUri(activity, opFolder.toUri())
                    ?: return Long.MAX_VALUE
                val uri = DocumentsContract.buildDocumentUriUsingTree(
                    tree.uri,
                    DocumentsContract.getDocumentId(tree.uri)
                )
                val descr = activity.contentResolver.openFileDescriptor(uri, "r")
                    ?.fileDescriptor// ?: return Long.MAX_VALUE
                return Os.fstatvfs(descr).run { f_bavail * f_bsize }
            } else if (opFolder.isFile()) return opFolder.toFolder().usableSpace
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Long.MAX_VALUE
    }
}