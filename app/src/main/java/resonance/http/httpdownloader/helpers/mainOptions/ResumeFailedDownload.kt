package resonance.http.httpdownloader.helpers.mainOptions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.*
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.UriFile

@SuppressLint("SetTextI18n", "InflateParams")
class ResumeFailedDownload(private val factory: QuestionFactory) : MainOption(factory.activity) {
    private companion object {
        const val DOWNLOAD_DATA = "downloadData"
        const val RESPONSE = "response"
        const val URI_FILE = "uriFile"
        const val FAILED_FILE_SIZE = "fileSize"
    }

    private val parent = factory.parent
    private val tempStorage = factory.tempStorage
    private val reachedQuestions: MutableList<String>
        get() = factory.reachedQuestions

    private var uriFile: UriFile? = null

    private lateinit var option2: Option
    override fun make() = Option(
        activity = activity,
        option = "Resume a failed download",
        short = "resumeFailed",
        onInit = null,
        onClick = {
            it.isEnabled = false
            factory.question0.removeAllBut(1)
            mainQuestion().initialize().addTo(parent)
        }
    ).also { option2 = it }

    private lateinit var mainQuestion: Question
    private fun mainQuestion(): Question = Question(
        activity = activity,
        name = "resumeFailedDownload",
        _description = "Resume most of the failed downloads even from other apps. HTTP-Downloader can download remaining part of your failed download & thus recover your failed download." +
                "<br><i>NB: Even though this feature works well with most apps, <b>We don't guarantee 100% compatibility with any browsers/download managers</b> except HTTP-Downloader</i>",
        _question = "<b>Go to the website and click on failed download's link to continue</b>",
        onInit = null,
        onGoBack = { factory.reAdd(factory.question0, factory.question0()) },
        onGoForward = {
            if (reachedQuestions.contains("failedFromWhere")) {
                (tempStorage[DOWNLOAD_DATA] as JSONObject).apply {
                    factory.downloadData = this
                    if (getString(C.misc.from) == C.misc.browser) {
                        it.removeAllBut(0)
                        clickToOpenBrowser.isEnabled = false
                        clickToOpenBrowser.disabledReason =
                            "Press back button to enable this option"
                    } else if (getString(C.misc.from) == C.misc.shared) {
                        it.removeAllBut(1)
                        fromShared.isEnabled = false
                        fromShared.disabledReason = "Press back button to enable this option"
                    }
                }
                if (tempStorage.containsKey(RESPONSE))
                    response = tempStorage[RESPONSE] as DownloadObject.ResponseData
                failedFromWhere().initialize().addTo(parent)

                reachedQuestions.contains("selectFailedFile")
                        || reachedQuestions.contains("enterFileSize")
            } else false
        }
    ).apply {
        mainQuestion = this
        addOption(clickToOpenBrowser())
        addOption(fromShared())
    }

    private lateinit var clickToOpenBrowser: Option
    private fun clickToOpenBrowser(): Option {
        return factory.clickToOpenBrowser {
            response = null
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            clickToOpenBrowser.isEnabled = false
            mainQuestion.removeAllBut(0)
            failedFromWhere()
        }.also { clickToOpenBrowser = it }
    }

    private lateinit var fromShared: Option
    private fun fromShared(): Option {
        return factory.fromShared {
            response = null
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            fromShared.isEnabled = false
            mainQuestion.removeAllBut(1)
            failedFromWhere()
        }.also { fromShared = it }
    }

    private lateinit var failedFromWhere: Question
    private fun failedFromWhere(): Question = Question(
        activity = activity,
        name = "failedFromWhere",
        _description = null,
        _question = "<b>From which device did the download fail?</b>",
        onInit = { uriFile = null },
        onGoBack = { factory.reAdd(mainQuestion, mainQuestion()) },
        onGoForward = {
            when {
                reachedQuestions.contains("selectFailedFile") -> {
                    fromThisDevice.click()
                    reachedQuestions.contains("failedAtPosition")
                }
                reachedQuestions.contains("enterFileSize") -> {
                    someOtherDevice.click()
                    reachedQuestions.contains("downloadDetails2")
                }
                else -> false
            }
        }
    ).apply {
        failedFromWhere = this
        addOption(fromThisDevice())
        addOption(someOtherDevice())
    }

    private lateinit var fromThisDevice: Option
    private fun fromThisDevice(): Option = Option(
        activity = activity,
        option = "In this device",
        short = "thisDevice",
        onInit = null,
        onClick = {
            it.isEnabled = false
            failedFromWhere.removeAllBut(0)
            selectFailedFile().initialize().addTo(parent)
        }
    ).also { fromThisDevice = it }

    private lateinit var someOtherDevice: Option
    private fun someOtherDevice(): Option = Option(
        activity = activity,
        option = "In some other device",
        short = "otherDevice",
        onInit = null,
        onClick = {
            it.isEnabled = false
            failedFromWhere.removeAllBut(1)
            enterFileSize().initialize().addTo(parent)
        }
    ).also { someOtherDevice = it }

    private lateinit var selectFailedFile: Question
    private fun selectFailedFile(): Question = Question(
        activity = activity,
        name = "selectFailedFile",
        _description = null,
        _question = "<b>Select the failed file from file manager</b>",
        onInit = {
            if (!reachedQuestions.contains("failedAtPosition"))
                uriFile = null
        },
        onGoBack = {
            factory.downloadData = factory.downloadData.apply { remove(C.dt.fileName) }
            factory.reAdd(failedFromWhere, failedFromWhere())
        },
        onGoForward = {
            if (reachedQuestions.contains("failedAtPosition")) {
                continueOption.click()
                reachedQuestions.contains("downloadDetails1")
                        || reachedQuestions.contains("weCantHelpU")
            } else false
        }
    ).apply {
        selectFailedFile = this
        addOption(openFileManager())
        if (reachedQuestions.contains("failedAtPosition"))
            addOption(continueOption())
    }

    private lateinit var openFileManager: Option
    private fun openFileManager(): Option = Option(
        activity = activity,
        option = "Click to open file manager",
        short = "openFileManager",
        onInit = {
            if (reachedQuestions.contains("failedAtPosition")) {
                uriFile = tempStorage[URI_FILE] as UriFile
                it.text = "Failed file: <b>${uriFile?.name}</b>"
            }
        },
        onClick = {
            selectFailedFile.removeAllBut(0)
            factory.onActivityResult[QuestionFactory.FILE_SELECT] =
                { requestCode: Int, resultCode: Int, data: Intent? ->
                    if (requestCode == 251 && resultCode == Activity.RESULT_OK) {
                        data?.data?.also { uri ->
                            val file = { UriFile(activity, uri.str) } otherwise null
                            if (file != null) {
                                factory.downloadData = factory.downloadData.apply {
                                    put(C.dt.outputFolder, "${C.type.fileUri}:$uri")
                                    tempStorage[DOWNLOAD_DATA] = this
                                }
                                it.text = "Failed file: <b>${file.name}</b>"
                                tempStorage[URI_FILE] = file
                                uriFile = file
                                selectFailedFile.addOption(continueOption())
                            } else activity.showSnackBar("Could not access this file")
                        }
                    } else {
                        it.text = "Click to open file manager"
                        factory.showSnackBar(
                            "Can't find your required file?\n" +
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
                                activity.showSnackBar(
                                    "No browsers detected. Please install a browser and try again",
                                    duration = Snackbar.LENGTH_LONG
                                )
                            }
                        }
                    }
                    factory.onActivityResult.remove(QuestionFactory.FILE_SELECT)
                }
            with(Intent(Intent.ACTION_CREATE_DOCUMENT)) {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                activity.startActivityForResult(this, 251)
            }
        }
    ).also { openFileManager = it }

    private lateinit var continueOption: Option
    private fun continueOption(): Option = Option(
        activity = activity,
        option = "Checking..",
        short = null,
        onInit = { option ->
            option.isEnabled = false
            option.disabledReason = "We are checking whether this file can be resumed. Please wait"
            val progressBar = ProgressBar(activity).apply {
                layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 5.dp }
            }
            val holder = option.view.findViewById<LinearLayout>(R.id.holder)
            holder.addView(progressBar)

            runWithResponse(
                data = factory.downloadData,
                bytesToRead = 50.KB,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    holder.removeView(progressBar)
                    if (continueOptionVerify(response, option, uriFile)) {
                        option.text = "Continue"
                        option.isEnabled = true
                    }
                },
                onError = {
                    option.text = "<b><font color='#f2a983'>Network Error</font></b>"
                    option.disabledReason = "Can't reach servers. Go back & try again"
                    activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
                    holder.removeView(progressBar)
                }
            )
        },
        onClick = {
            it.isEnabled = false
            openFileManager.isEnabled = false
            selectFailedFile.removeAllBut(0)
            failedAtPosition().initialize().addTo(parent)
        }
    ).apply { continueOption = this }

    private lateinit var failedAtPosition: Question
    private fun failedAtPosition(): Question = Question(
        activity = activity,
        name = "failedAtPosition",
        _description = null,
        _question = "<b>Is that right?</b>",
        onInit = {
            val size = uriFile?.size
            if (size == null) {
                factory.showSnackBar("Could not determine size of selected file. Please try again")
                it.onGoBack(it)
            } else {
                it.description = "Your download failed at <b><font color='#2875c0'>${formatSize(
                    size,
                    3,
                    " "
                )}</font></b>"
            }
        },
        onGoBack = { factory.reAdd(selectFailedFile, selectFailedFile()) },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails1")) yes.click()
            else if (reachedQuestions.contains("weCantHelpU")) no.click()
            false
        }
    ).apply {
        failedAtPosition = this
        addOption(yes())
        addOption(no())
    }

    private lateinit var yes: Option
    private fun yes(): Option = Option(
        activity = activity,
        option = "Yes",
        onInit = null,
        onClick = {
            it.isEnabled = false
            failedAtPosition.removeAllBut(0)
            downloadDetails1().initialize().addTo(parent)
        }
    ).also { yes = it }

    private lateinit var no: Option
    private fun no(): Option = Option(
        activity = activity,
        option = "No",
        onInit = null,
        onClick = {
            it.isEnabled = false
            failedAtPosition.removeAllBut(1)
            weCantHelpU().initialize().addTo(parent)
        }
    ).also { no = it }

    private lateinit var downloadDetails1: Question
    private fun downloadDetails1(): Question = Question(
        activity = activity,
        name = "downloadDetails1",
        _description = null,
        _question = "Here are some details of your download. Click on <b>start download</b> to begin",
        onInit = null,
        onGoBack = { factory.reAdd(failedAtPosition, failedAtPosition()) }
    ).apply {
        downloadDetails1 = this
        addOption(fileName())
        addOption(
            factory.totalSize(
                prefix = "Full download size",
                mainOption = this@ResumeFailedDownload
            )
        )
        addOption(failedAt1())
        addOption(remaining1())
        addOption(factory.startDownload(this@ResumeFailedDownload))
    }

    private lateinit var fileName: Option
    private fun fileName(): Option = Option(
        activity = activity,
        option = "Failed file: <b>${uriFile!!.name}</b>",
        short = "failedFileName",
        onInit = {
            factory.downloadData = factory.downloadData.apply {
                put(C.dt.fileName, uriFile!!.name)
                tempStorage[DOWNLOAD_DATA] = this
            }
            it.isEnabled = false
            it.disabledReason = "You can't modify this value"
        },
        onClick = null
    ).also { fileName = it }

    private lateinit var failedAt1: Option
    private fun failedAt1(): Option = Option(
        activity = activity,
        option = "Failed at: <font color='#f99797'><b>" +
                formatSize(uriFile!!.size, 3, " ") + "</b></font>",
        short = "failedPosition",
        onInit = {
            it.isEnabled = false
            it.disabledReason = "You can't modify this value"
        },
        onClick = null
    ).also { failedAt1 = it }

    private lateinit var remaining1: Option
    private fun remaining1(): Option = Option(
        activity = activity,
        option = "Remaining: loading...",
        short = "remaining",
        onInit = { option ->
            option.isEnabled = false
            option.disabledReason = "You can't modify this value"
            if (uriFile == null) {
                option.text = "Remaining: <b><font color='#f2a983'>ERROR</font></b>"
                return@Option
            }
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    option.text = "Remaining: <font color='#9ee299'><b>" +
                            formatSize(response.total - uriFile!!.size, 3, " ") +
                            "</b></font>"
                },
                onError = { option.text = "Remaining: <b><font color='#f2a983'>ERROR</font></b>" }
            )
        },
        onClick = null
    ).also { remaining1 = it }

    private lateinit var enterFileSize: Question
    private fun enterFileSize(): Question = Question(
        activity = activity,
        name = "enterFileSize",
        _description = "Find the exact file size (in bytes) of the failed file (see how: https://resonance00x0.github.io/http-downloader/faq#how-to-find-exact-size-of-failed-file-in-bytes). Don't get confused with <b><i>size on disk</i></b>",
        _question = "Enter the exact size (<font color='orange'>in bytes</font>) of the failed file",
        onInit = null,
        onGoBack = { factory.reAdd(failedFromWhere, failedFromWhere()) },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails2"))
                fileSizeContinue.click()
            false
        }
    ).apply {
        enterFileSize = this
        addOption(fileSize())
        addOption(fileSizeContinue())
    }

    private lateinit var fileSize: Option
    private fun fileSize(): Option = Option(
        activity = activity,
        option = "",
        short = "fileSize",
        onInit = {
            it.view = activity.layoutInflater.inflate(R.layout.file_size_option, null)
            it.view.apply {
                val warning = findViewById<ImageView>(R.id.warning)
                val text = findViewById<EditText>(R.id.text)
                val formatted = findViewById<TextView>(R.id.formattedSize)

                text.requestFocus()
                warning.setOnClickListener {
                    val size = { text.text.str.toLong() } otherwise 0L
                    activity.showSnackBar(
                        "Probably ${formatSize(size, 3, " ")} may not be" +
                                " the size of your failed file",
                        duration = Snackbar.LENGTH_SHORT
                    )
                }
                text.setOnTextChangedListener {
                    val size = { it.str.toLong() } otherwise 0L
                    formatted.text = "(= ${formatSize(size, 2, " ")})"
                    if (size < 10.MB || size > 20.GB)
                        warning.unHide()
                    else warning.setGone()
                }
                if (reachedQuestions.contains("downloadDetails2")) {
                    val size = tempStorage[FAILED_FILE_SIZE] as String
                    text.setText(size)
                    text.setSelection(size.length)
                }
            }
        },
        onClick = null,
        useCustomView = true
    ).apply { fileSize = this }

    private lateinit var fileSizeContinue: Option
    private fun fileSizeContinue(): Option = Option(
        activity = activity,
        option = "Continue",
        short = null,
        onInit = null,
        onClick = {
            val size = {
                fileSize.view.findViewById<EditText>(R.id.text).text.str.toLong()
            } otherwise 0L

            fun proceed() {
                val progressBar = ProgressBar(activity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 5.dp }
                }
                it.isEnabled = false
                it.disabledReason = "Press back button to enable this option"
                fileSize.view.apply {
                    fileSize.view.findViewById<EditText>(R.id.text).isEnabled = false
                    fileSize.view.findViewById<ImageView>(R.id.warning).isEnabled = false
                    it.view.findViewById<LinearLayout>(R.id.holder).addView(progressBar)
                }
                factory.downloadData = factory.downloadData.apply {
                    put(C.dt.offset, size)
                    tempStorage[DOWNLOAD_DATA] = this
                }
                fileSizeContinueVerify(it, size, progressBar)
            }
            if (size < 10.MB || size > 20.GB) {
                AlertDialog.Builder(activity)
                    .setTitle("Are you sure?")
                    .setMessage(
                        ("<b>$size bytes = ${formatSize(size, 1, " ")}</b><br>" +
                                "Probably this may not be the size of your failed file<br>" +
                                "<font color='#FF6C72'>Are you sure to proceed anyway?</font>").asHtml()
                    )
                    .setPositiveButton("Proceed") { d, _ -> proceed(); d.dismiss() }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .show()
            } else {
                fileSize.view.findViewById<ImageView>(R.id.warning).setGone()
                proceed()
            }
        }
    ).apply { fileSizeContinue = this }

    private lateinit var downloadDetails2: Question
    private fun downloadDetails2(): Question = Question(
        activity = activity,
        name = "downloadDetails2",
        _description = null,
        _question = "Here are some details of your download. Click on <b>start download</b> to begin",
        onInit = null,
        onGoBack = { factory.reAdd(enterFileSize, enterFileSize()) }
    ).apply {
        downloadDetails2 = this
        addOption(factory.selectFolder(mainOption = this@ResumeFailedDownload))
        addOption(
            factory.fileName(
                option = "File name",
                mainOption = this@ResumeFailedDownload
            )
        )
        addOption(factory.totalSize("Full download size", this@ResumeFailedDownload))
        addOption(failedAt2())
        addOption(remaining2())
        addOption(factory.emptyFirstModeOption())
        addOption(factory.startDownload(this@ResumeFailedDownload))
    }

    private lateinit var failedAt2: Option
    private fun failedAt2(): Option = Option(
        activity = activity,
        option = "Failed at: <font color='#f99797'><b>" +
                "${formatSize(factory.downloadData.getLong(C.dt.offset), 3, " ")}</b></font>",
        short = "failedPoint",
        onInit = {
            it.isEnabled = false
            it.disabledReason = "If you need to modify this value, go back"
        },
        onClick = null
    ).apply { failedAt2 = this }

    private lateinit var remaining2: Option
    private fun remaining2(): Option = Option(
        activity = activity,
        option = "Remaining: loading...",
        short = "remaining2",
        onInit = { option ->
            option.isEnabled = false
            option.disabledReason = "You can't modify this value"
            val data = factory.downloadData
            if (!data.has(C.dt.offset)) {
                option.text = "Remaining: <b><font color='#f2a983'>ERROR</font></b>"
                return@Option
            }
            val offset = data.getLong(C.dt.offset)
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    option.text = "Remaining: <font color='#9ee299'><b>${formatSize(
                        response.total - offset,
                        3,
                        " "
                    )}</b></font>"
                },
                onError = { option.text = "Remaining: <b><font color='#f2a983'>ERROR</font></b>" }
            )
        },
        onClick = null
    ).also { remaining2 = it }

    private lateinit var weCantHelpU: Question
    private fun weCantHelpU(): Question = Question(
        activity = activity,
        name = "weCantHelpU",
        _description = "Probably the previous app is using some non-standard method to download files.",
        _question = "<b><font color=red>We are sorry. We can't help you to resume this file</font></b>",
        onInit = null,
        onGoBack = { factory.reAdd(failedAtPosition, failedAtPosition()) }
    ).apply { weCantHelpU = this }

    /**
     * Verifies the already received response is valid.
     * Updates parameters of option in case of invalid response
     */
    private fun continueOptionVerify(
        response: DownloadObject.ResponseData,
        option: Option,
        file: UriFile?
    ): Boolean {
        if (file == null) {
            option.text = "<b><font color='#f2a983'>Can't access file</font></b>"
            option.disabledReason = "We are unable to access the failed file<br>" +
                    "<b>Re-select the file to continue</b>"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        if (!verify(response, option, file.size)) return false

        //first 50KB (sample data to check whether files are same) of source & destination files
        var remote = response.bytes
        val local = file.readNBytes(remote.size)

        //equalizing array lengths to minimum length
        remote = remote[0 to local.size]

        if (!local.contentEquals(remote)) {
            option.text = "<b><font color='#f2a983'>Wrong file</font></b>"
            option.disabledReason = "The selected file doesn't match your download.<br>" +
                    "<b>Select correct file</b>"
            silently {
                log(
                "Files verify", "unequal contents; wrong file?\n",
                local[0 to 30].asList(), '\n', remote[0 to 30].asList(), "\n\n",
                local[-30 to -1].asList(), '\n', remote[-30 to -1].asList()
                )
            }
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        return true
    }

    /**
     * Fetches DownloadObject.ResponseData asynchronously & verifies it for errors
     * in case of no errors this function adds question: downloadDetails2
     */
    private fun fileSizeContinueVerify(option: Option, size: Long, progressBar: ProgressBar) {
        fun gotoNextQuestion(size: Long) {
            tempStorage[FAILED_FILE_SIZE] = size.str
            enterFileSize.removeAllBut(0)
            downloadDetails2().initialize().addTo(parent)
        }
        runWithResponse(
            data = factory.downloadData,
            onSuccess = { response ->
                tempStorage[RESPONSE] = response
                option.view.findViewById<LinearLayout>(R.id.holder).removeView(progressBar)
                if (verify(response, option, size)) gotoNextQuestion(size)
                else option.view.findViewById<LinearLayout>(R.id.holder).removeView(progressBar)
            },
            onError = { e ->
                option.view.findViewById<LinearLayout>(R.id.holder).removeView(progressBar)
                option.text = "<b><font color='#f2a983'>ERROR</font></b>"
                if (e == null) activity.showSnackBar("Network error. Please try again")
                else activity.showSnackBar(getExceptionCause(e, "ResumeFailedDownload"))
            }
        )
    }

    /**
     * Verifies whether
     * @param response is valid for given file
     * @param size
     * Automatically shows errors as snackBar, disables
     * @param option, set disabledReason & update option's text
     */
    private fun verify(
        response: DownloadObject.ResponseData,
        option: Option,
        size: Long
    ): Boolean {
        if (response.code != 200 && response.code != 206 && response.code != 202) {
            option.isEnabled = false
            option.text = "<b><font color='#f2a983'>Wrong response</font></b>"
            option.disabledReason = "The server returned response code ${response.code}<br>" +
                    "<font color=red>Go back and try again.</font>"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        if (response.isWebPage) {
            option.isEnabled = false
            option.text = "<b><font color='#f2a983'>File not downloadable</font></b>"
            option.disabledReason = "The server returned a web-page instead of file<br>" +
                    "<font color=red>Go back and try again.</font>"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        if (response.isContentLengthType) {
            option.isEnabled = false
            option.text = "<b><font color='#f2a983'>Website disallowed download resuming</font></b>"
            option.disabledReason = "This server doesn't allow download resuming.<br>" +
                    "<font color=red>Sorry, we can't help you</font>"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        if (response.total < size) {
            option.isEnabled = false
            option.text = "<b><font color='#f2a983'>Wrong file</font></b>"
            option.disabledReason =
                "The selected file's size is greater than your download's size.<br>" +
                        "<b>Select correct file</b>"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        if (response.total == size) {
            option.text = "<b><font color='#c6e6fc'>Download already finished</font></b>"
            option.disabledReason = "The selected download has already been completed"
            activity.showSnackBar(option.disabledReason, duration = Snackbar.LENGTH_LONG)
            return false
        }
        return true
    }

    private val Int.dp: Int
        get() {
            val metrics = activity.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}