package resonance.http.httpdownloader.helpers.mainOptions

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.util.DisplayMetrics
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import kotlin.collections.set
import kotlin.math.min

@SuppressLint("InflateParams")
class MultiDeviceDownload(private val factory: QuestionFactory) : MainOption(factory.activity) {
    private companion object {
        const val DEVICE_COUNT = "deviceCount"
        const val N = "n"
        const val DOWNLOAD_DATA = "downloadData"
        const val RESPONSE = "response"
    }

    private val tempStorage = factory.tempStorage
    private val parent = factory.parent
    private val reachedQuestions: MutableList<String>
        get() = factory.reachedQuestions

    private var devicesNumber = 2
    private var partIndex = 1
    private lateinit var downloadRange: Pair<Long, Long>

    private lateinit var option3: Option
    override fun make(): Option = Option(
        activity = activity,
        option = "Download using 2 (<i>or more</i>) devices",
        short = "multiDeviceDownload",
        onInit = null,
        onClick = {
            it.isEnabled = false
            factory.question0.removeAllBut(2)
            mainQuestion().initialize().addTo(parent)
        }
    ).also { option3 = it }

    private lateinit var mainQuestion: Question
    private fun mainQuestion(): Question = Question(
        activity = activity,
        name = "multiDeviceDownload",
        _description = "You can finish your download <b>2X faster</b> using 2 devices. " +
                "Multiple devices can download different parts of your download at same time thereby increasing download speed. " +
                "<b>After downloading, you need to join these files before using</b> (see how: https://resonance00x0.github.io/http-downloader/file-joiner)",
        _question = "<b>How many devices do you have?</b>",
        onInit = null,
        onGoBack = { factory.reAdd(factory.question0, factory.question0()) },
        onGoForward = {
            if (reachedQuestions.contains("whichPart")) {
                deviceCount.view.findViewById<EditText>(R.id.count)
                    .setText(tempStorage[DEVICE_COUNT].str)
                deviceCountContinue.click()
                reachedQuestions.contains("getDownloadData")
            } else false
        }
    ).apply {
        mainQuestion = this
        addOption(deviceCount())
        addOption(deviceCountContinue())
    }

    private lateinit var deviceCount: Option
    private fun deviceCount(): Option = Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = factory.counterView(2, 99).apply {
                if (reachedQuestions.contains("whichPart"))
                    findViewById<EditText>(R.id.count).setText(tempStorage[DEVICE_COUNT].str)
            }
        },
        onClick = null,
        useCustomView = true
    ).also { deviceCount = it }

    private lateinit var deviceCountContinue: Option
    private fun deviceCountContinue(): Option = Option(
        activity = activity,
        option = "Continue",
        short = null,
        onInit = null,
        onClick = {
            val count = deviceCount.view.findViewById<EditText>(R.id.count)
            if (count.count >= 2) {
                it.isEnabled = false
                mainQuestion.removeAllBut(0)
                (deviceCount.view as LinearLayout).apply {
                    removeView(findViewById(R.id.down))
                    removeView(findViewById(R.id.up))
                    count.isEnabled = false
                    devicesNumber = count.count
                }
                tempStorage[DEVICE_COUNT] = devicesNumber
                deviceCount.view.findViewById<EditText>(R.id.count).setSelection(0)
                whichPart().initialize().addTo(parent)
            } else activity.showSnackBar("You need minimum 2 devices to use this feature")
        }
    ).also { deviceCountContinue = it }

    private lateinit var whichPart: Question
    private fun whichPart(): Question = Question(
        activity = activity,
        name = "whichPart",
        _description = null,
        _question = "<b>Which part do you like to download in <font color='#11C457'>this device?</font></b>",
        onInit = null,
        onGoBack = { factory.reAdd(mainQuestion, mainQuestion()) },
        onGoForward = {
            if (reachedQuestions.contains("getDownloadData")) {
                nthPart.view.findViewById<EditText>(R.id.count)
                    .setText(tempStorage[N].str)
                nthPartContinue.click()
                reachedQuestions.contains("downloadDetails")
            } else false
        }
    ).apply {
        whichPart = this
        addOption(nthPart())
        addOption(nthPartContinue())
    }

    private lateinit var nthPart: Option
    private fun nthPart(): Option = Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = factory.counterView(1, devicesNumber).apply {
                if (reachedQuestions.contains("getDownloadData"))
                    findViewById<EditText>(R.id.count).setText(tempStorage[N].str)
            }
        },
        onClick = null,
        useCustomView = true
    ).also { nthPart = it }

    private lateinit var nthPartContinue: Option
    private fun nthPartContinue(): Option = Option(
        activity = activity,
        option = "Continue",
        short = null,
        onInit = null,
        onClick = {
            it.isEnabled = false
            whichPart.removeAllBut(0)
            (nthPart.view as LinearLayout).apply {
                removeView(findViewById(R.id.down))
                removeView(findViewById(R.id.up))
                findViewById<EditText>(R.id.count).isEnabled = false
            }
            partIndex = nthPart.view.findViewById<EditText>(R.id.count).count
            tempStorage[N] = partIndex
            nthPart.view.findViewById<EditText>(R.id.count).setSelection(0)
            getDownloadData().initialize().addTo(parent)
        }
    ).also { nthPartContinue = it }

    private lateinit var getDownloadData: Question
    private fun getDownloadData(): Question = Question(
        activity = activity,
        name = "getDownloadData",
        _description = null,
        _question = "<b>How do you like to start download?</b>",
        onInit = null,
        onGoBack = { factory.reAdd(whichPart, whichPart()) },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails")) {
                (tempStorage[DOWNLOAD_DATA] as JSONObject).apply {
                    factory.downloadData = this
                    if (getString(C.misc.from) == C.misc.browser) {
                        it.removeAllBut(0)
                        fromBrowser.isEnabled = false
                        fromBrowser.disabledReason = "Press back button to enable this option"
                    } else if (getString(C.misc.from) == C.misc.shared) {
                        it.removeAllBut(1)
                        fromShared.isEnabled = false
                        fromShared.disabledReason = "Press back button to enable this option"
                    }
                }
                if (tempStorage.containsKey(RESPONSE))
                    response = tempStorage[RESPONSE] as DownloadObject.ResponseData
                downloadDetails().initialize().addTo(parent)
            }
            false
        }
    ).apply {
        getDownloadData = this
        addOption(fromBrowser())
        addOption(fromShared())
    }

    private lateinit var fromBrowser: Option
    private fun fromBrowser(): Option {
        response = null
        return factory.clickToOpenBrowser(
            option = "Start download from browser",
            nextQuestion = {
                getDownloadData.removeAllBut(0)
                tempStorage[DOWNLOAD_DATA] = factory.downloadData
                fromBrowser.isEnabled = false
                getDownloadData.removeAllBut(0)
                downloadDetails()
            }
        ).also { fromBrowser = it }
    }

    private lateinit var fromShared: Option
    private fun fromShared(): Option {
        response = null
        return factory.fromShared {
            getDownloadData.removeAllBut(1)
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            fromShared.isEnabled = false
            getDownloadData.removeAllBut(1)
            downloadDetails()
        }.also { fromShared = it }
    }

    private lateinit var downloadDetails: Question
    private fun downloadDetails(): Question = Question(
        activity = activity,
        name = "downloadDetails",
        _description = null,
        _question = "<b>Here are some details about your download. Click on them to configure if you need</b>",
        onInit = null,
        onGoBack = { factory.reAdd(getDownloadData, getDownloadData()) },
        onGoForward = { false }
    ).apply {
        downloadDetails = this
        addOption(url())
        addOption(factory.totalSize("Full file size", this@MultiDeviceDownload))
        addOption(factory.selectFolder("Folder", this@MultiDeviceDownload))
        addOption(
            factory.fileName(
                option = "Name",
                fileNameSuffix = " ($partIndex of $devicesNumber)",
                mainOption = this@MultiDeviceDownload
            )
        )
        addOption(factory.emptyFirstModeOption())
        addOption(yourShare())
        addOption(downloadVisual())
        addOption(shareDownloadData())
        addOption(factory.startDownload(this@MultiDeviceDownload).apply {
            isEnabled = false
            disabledReason = "Please wait while we fetch details of the download"
            val showError: (Exception?) -> Unit = {
                isEnabled = false
                disabledReason = getExceptionCause(it, "")
                activity.showSnackBar(getExceptionCause(it, ""))
            }
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    factory.downloadData = factory.downloadData.apply {
                        //setting offset & limit
                        downloadRange = getRangeOf(partIndex, devicesNumber, response.total)
                        put(C.dt.offset, downloadRange.first)
                        put(C.dt.limit, downloadRange.second)
                        // re-fetching download details to check whether updated offset & limit are acceptable
                        runWithResponse(
                            data = this,
                            onSuccess = { isEnabled = true },
                            onError = showError
                        )
                    }
                },
                onError = showError
            )
        })
    }

    private fun yourShare(): Option = Option(
        activity = activity,
        option = "This device's share: loading...",
        short = "this device's share",
        onInit = { option ->
            option.isEnabled = false
            option.disabledReason = "Please wait until data is fetched"
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    option.isEnabled = true
                    downloadRange = getRangeOf(partIndex, devicesNumber, response.total)
                    val share = formatSize(downloadRange.second - downloadRange.first, 2, " ")
                    val from = if (downloadRange.first == 0L) "start" else formatSize(
                        downloadRange.first,
                        2,
                        " "
                    )
                    val to = if (downloadRange.second == response.total) "end" else formatSize(
                        downloadRange.second,
                        2
                    )
                    option.text = "This device's share: <b>$share ($from to $to)</b>"
                },
                onError = {
                    option.disabledReason = getExceptionCause(it, "MultiDeviceDownload")
                    option.text = "This device's share: " +
                            "<b><font color='#f2a983'>Network Error</font></b>"
                }
            )
        },
        onClick = {
            val msg = """<font color=red><b>Manually changing device's share is dangerous</b></font>.<br>
                    |If you make any mistakes in the byte position, file will get corrupted.<br><br>
                    |<b>So this option is disabled here.</b><br><br>
                    |Go to <b>home</b> and click on <b>Download a file part</b> if you still wish to proceed manually
                    |""".trimMargin().trim()
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Warning")
                .setCancelable(true)
                .setMessage(msg.asHtml())
                .setPositiveButton("Go home"){d,_ -> activity.home(it.view); d.dismiss() }
                .setNegativeButton("Cancel"){d,_ -> d.dismiss()}
                .create()
            dialog.setOnShowListener {
                val color = ContextCompat.getColor(activity, R.color.colorAccent_dark)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color)
            }
            dialog.show()
        }
    )

    private fun shareDownloadData(): Option {
        fun shareTextually(data: String) {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "HTTP-Downloader download data")
            intent.putExtra(Intent.EXTRA_TEXT, data)
            activity.startActivity(Intent.createChooser(intent, "Share via:"))
        }

        fun showShareQR(data: String) {
            val view = activity.layoutInflater.inflate(R.layout.qr_code, null)
            val imageView = view.findViewById<ImageView>(R.id.qrCode)
            val lockIcon = view.findViewById<ImageView>(R.id.lockIcon)

            val metrics = DisplayMetrics()
            activity.windowManager.defaultDisplay.getMetrics(metrics)
            val size = (min(metrics.heightPixels, metrics.widthPixels) * 0.8).toInt()

            imageView.layoutParams = imageView.layoutParams.apply { width = size; height = size }

            AlertDialog.Builder(activity).setView(view).show()
            CoroutineScope(Dispatchers.Main).launch {
                generateQRCode(data, size)?.also {
                    view.findViewById<ProgressBar>(R.id.progress).hide()
                    imageView.setImageBitmap(it)
                } ?: activity.showSnackBar("Some error occurred. Please try again")
            }
        }
        return Option(
            activity = activity,
            option = "<b><u>Share download data</u></b>",
            short = "shareData",
            onInit = null,
            onClick = {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.share_title)
                    .setMessage(R.string.share_warning)
                    .setPositiveButton("Share") { d, _ ->
                        log("MultiDeviceDownload", "shareDownloadData: +ve")
                        shareTextually(factory.downloadData.str)
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel") { d, _ ->
                        log("MultiDeviceDownload", "shareDownloadData: -ve")
                        d.dismiss()
                    }
                    .setNeutralButton("QR code") { d, _ ->
                        log("MultiDeviceDownload", "shareDownloadData: qr")
                        showShareQR(factory.downloadData.str)
                        d.dismiss()
                    }
                    .setCancelable(true)
                    .show()
            }
        )
    }

    private fun downloadVisual(): Option = Option(
        activity = activity,
        option = "Download visual",
        short = "visual",
        onInit = { option ->
            option.view = LinearLayout(activity)
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    downloadRange = getRangeOf(partIndex, devicesNumber, response.total)
                    (option.view as LinearLayout)
                        .addView(
                            getDownloadVisual(
                                activity,
                                response.total,
                                downloadRange.first,
                                downloadRange.second
                            )
                        )
                    activity.scrollToBottom()
                },
                onError = {}
            )
        },
        onClick = null,
        useCustomView = true
    )

    private fun url(): Option = Option(
        activity = activity,
        option = "URL: <b>${factory.downloadData.getString(C.dt.url)}</b>",
        short = "url",
        onInit = {
            it.isEnabled = false
            it.disabledReason = "You cannot change URL from here.<br>" +
                    "<font color=red><b>Go back to change url</b></font>"
        },
        onClick = null
    )

    private var EditText.count: Int
        get() = { text.str.toInt() } otherwise 2
        set(value) {
            val txt = value.str; setText(txt); setSelection(txt.length)
        }
}