package resonance.http.httpdownloader.helpers.mainOptions

import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*

class MultiPartDownload(private val factory: QuestionFactory) : MainOption(factory.activity) {
    private companion object {
        const val PARTS_COUNT = "partsCount"
        const val IS_PARALLEL = "isParallel"
        const val DOWNLOAD_DATA = "downloadData"
        const val RESPONSE = "response"
    }

    private val tempStorage = factory.tempStorage
    private val parent = factory.parent
    private val reachedQuestions: MutableList<String>
        get() = factory.reachedQuestions

    private var totalSize = 0L
    private var isParallel: Boolean? = null

    private lateinit var option4: Option
    override fun make(): Option = Option(
        activity = activity,
        option = "Download as <b>several parts</b>",
        short = "multiPartDownload",
        onInit = {
            it.isEnabled = true
        },
        onClick = {
            it.isEnabled = false
            factory.question0.removeAllBut(4)
            mainQuestion().initialize().addTo(parent)
        }
    ).also {
        option4 = it
    }

    private lateinit var mainQuestion: Question
    private fun mainQuestion(): Question = Question(
        activity = activity,
        name = "multiPartDownload",
        _description = "Some servers intentionally limit speed per connection. In such cases, HTTP-Downloader can request the file using <b>multiple connections to boost the overall speed.</b>" +
                "Also, if your device storage is low, this option allows you to download as much as your storage allows. " +
                "When storage is full, you can copy completed parts to any other storage medium and resume the download by downloading remaining parts",
        _question = "<b>Go to browser & click on your download link to continue</b>",
        onInit = null,
        onGoBack = { factory.reAdd(factory.question0, factory.question0()) },
        onGoForward = {
            if (reachedQuestions.contains("howManyParts")) {
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
                howManyParts().initialize().addTo(parent)
                reachedQuestions.contains("downloadStyle")
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
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            response = null
            clickToOpenBrowser.isEnabled = false
            mainQuestion.removeAllBut(0)
            howManyParts()
        }.also { clickToOpenBrowser = it }
    }

    private lateinit var fromShared: Option
    private fun fromShared(): Option {
        return factory.fromShared {
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            response = null
            fromShared.isEnabled = false
            mainQuestion.removeAllBut(1)
            howManyParts()
        }.also { fromShared = it }
    }

    private lateinit var howManyParts: Question
    private fun howManyParts(): Question = Question(
        activity = activity,
        name = "howManyParts",
        _description = null,
        _question = "<b>To how many parts do you wish to split this download?</b>",
        onInit = null,
        onGoBack = {
            tempStorage[PARTS_COUNT] = partsCount.view.findViewById<EditText>(R.id.count).count
            factory.reAdd(mainQuestion, mainQuestion())
        },
        onGoForward = {
            if (reachedQuestions.contains("downloadStyle")) {
                partsCountContinue.click()
                reachedQuestions.contains("downloadDetails")
            } else false
        }
    ).apply {
        howManyParts = this
        addOption(partsCount())
        addOption(partsCountDetails())
        addOption(partsCountContinue())
    }

    private lateinit var partsCount: Option
    private fun partsCount(): Option = Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = factory.counterView(2, 99) { editText ->
                val partsNumber = editText.count
                partsCountDetails.text = "${formatSize(totalSize, 2, " ")} as $partsNumber parts " +
                        "<b>(${formatSize(totalSize / partsNumber, 2, " ")} per part)</b>"
            }.apply {
                if (reachedQuestions.contains("downloadStyle"))
                    findViewById<EditText>(R.id.count).setText(tempStorage[PARTS_COUNT].str)
            }
        },
        onClick = null,
        useCustomView = true
    ).also { partsCount = it }

    private lateinit var partsCountDetails: Option
    private fun partsCountDetails(areDownloadsParallel: Boolean? = null): Option = Option(
        activity = activity,
        option = "Details: loading...",
        short = "details",
        onInit = {
            it.isEnabled = false
            it.disabledReason =
                "This is details of your download. To modify this value, <b>change number of parts</b>"
            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    tempStorage[RESPONSE] = response
                    val partsNumber = partsCount.view.findViewById<EditText>(R.id.count).count
                    totalSize = response.total
                    it.text = when (areDownloadsParallel) {
                        null -> "${formatSize(totalSize, 2, " ")} as $partsNumber parts " +
                                "<b>(${formatSize(totalSize / partsNumber, 2, " ")} per part)</b>"
                        true -> "$partsNumber parts each of ${formatSize(
                            totalSize / partsNumber,
                            2,
                            " "
                        )} in parallel"
                        false -> "$partsNumber parts each of ${formatSize(
                            totalSize / partsNumber,
                            2,
                            " "
                        )}, one after the other"
                    }
                },
                onError = { e ->
                    it.text = "Details: <b><font color='#f2a983'>ERROR</font></b>"
                    it.disabledReason = getExceptionCause(e, "MultiPartDownload")
                }
            )
        },
        onClick = null
    ).also { partsCountDetails = it }

    private lateinit var partsCountContinue: Option
    private fun partsCountContinue(): Option = Option(
        activity = activity,
        option = "Continue",
        short = null,
        onInit = null,
        onClick = {
            it.isEnabled = false
            howManyParts.removeAllBut(0)
            (partsCount.view as LinearLayout).apply {
                removeView(findViewById(R.id.down))
                removeView(findViewById(R.id.up))
                val count = findViewById<EditText>(R.id.count)
                count.isEnabled = false
            }
            tempStorage[PARTS_COUNT] = partsCount.view.findViewById<EditText>(R.id.count).count
            partsCount.view.findViewById<EditText>(R.id.count).setSelection(0)
            downloadStyle().initialize().addTo(parent)
        }
    ).also { partsCountContinue = it }

    private lateinit var downloadStyle: Question
    private fun downloadStyle(): Question = Question(
        activity = activity,
        name = "downloadStyle",
        _description = "<b>TIP:</b> choose <b><u>in parallel</u></b> if you think server is limiting your download speed.<br>" +
                "Choose <b><u>one after the other</u></b> if your remaining storage space is less than the whole file " +
                "or file system is FAT32/vFat see more at https://resonance00x0.github.io/http-downloader/#overcome-size-limits",
        _question = "<b>How do you like to download these parts?</b>",
        onInit = null,
        onGoBack = {
            tempStorage[IS_PARALLEL] = isParallel ?: false
            isParallel = null
            factory.reAdd(howManyParts, howManyParts())
        },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails")) {
                (tempStorage[IS_PARALLEL] as Boolean).also {
                    isParallel = it
                    if (it) inParallel.click()
                    else oneAfterTheOther.click()
                }
            }; false
        }
    ).apply {
        downloadStyle = this
        addOption(inParallel())
        addOption(oneAfterTheOther())
    }

    private lateinit var inParallel: Option
    private fun inParallel(): Option = Option(
        activity = activity,
        option = "in parallel",
        onInit = null,
        onClick = {
            it.isEnabled = false
            downloadStyle.removeAllBut(0)
            isParallel = true
            downloadDetails().initialize().addTo(parent)
        }
    ).also { inParallel = it }

    private lateinit var oneAfterTheOther: Option
    private fun oneAfterTheOther(): Option = Option(
        activity = activity,
        option = "one after the other",
        onInit = null,
        onClick = {
            it.isEnabled = false
            downloadStyle.removeAllBut(1)
            isParallel = false
            downloadDetails().initialize().addTo(parent)
        }
    ).also { oneAfterTheOther = it }

    private lateinit var downloadDetails: Question
    private fun downloadDetails(): Question = Question(
        activity = activity,
        name = "downloadDetails",
        _description = null,
        _question = "<b>Here are some details about your download. Click on them to configure if you need</b>",
        onInit = null,
        onGoBack = { factory.reAdd(downloadStyle, downloadStyle()) },
        onGoForward = { false }
    ).apply {
        downloadDetails = this

        val partsNumber = partsCount.view.findViewById<EditText>(R.id.count).count
        factory.downloadData = factory.downloadData.apply { put(C.dt.partsCount, partsNumber) }

        addOption(url())
        addOption(factory.totalSize("Full file size", this@MultiPartDownload))
        addOption(factory.selectFolder("Folder", this@MultiPartDownload))
        addOption(
            factory.fileName(
                option = "Name",
                mainOption = this@MultiPartDownload
            )
        )
        addOption(factory.emptyFirstModeOption())
        addOption(partsCountDetails(isParallel))
        addOption(factory.startDownload(this@MultiPartDownload) { data ->
            val count = partsCount.view.findViewById<EditText>(R.id.count).count
            val ids = LongArray(count) { getUniqueId() }
            val url = data.getString(C.dt.url)
            val opFolder = data.getString(C.dt.outputFolder)
            val headers = data.getString(C.dt.headers)
            val isEmptyFirst = data.optBoolean(C.dt.emptyFirstMode, false)
            val fileName = data.getString(C.dt.fileName)

            var offset = 0L
            var limit = totalSize / count + totalSize % count
            for (i in 0 until count) {
                val obj = JSONObject().apply {
                    put(C.dt.type, DownloadObject.TYPE)
                    put(C.dt.id, ids[count - i - 1]) //Downloads need to be ordered in reverse

                    if (isParallel == false) {
                        if (i != 0)
                            put(C.dt.startAfter, "ending ${ids[count - i]}")
                        if (i != count - 1)
                            put(C.dt.onEndAction, "start ${ids[count - i - 2]}")
                    }

                    put(C.dt.outputFolder, opFolder)
                    put(C.dt.url, url)
                    put(C.dt.offset, offset)
                    put(C.dt.limit, limit)
                    put(C.dt.headers, headers)
                    put(C.dt.emptyFirstMode, isEmptyFirst)
                    put(C.dt.fileName, addPartNumTo(fileName, i + 1))
                }

                offset = limit
                limit += totalSize / count

                obj.put(C.dt.isCollapsed, false)
                Intent(C.filter.START_DOWNLOAD).apply {
                    putExtra(C.misc.downloadDataJSON, obj.str)
                    activity.transferServiceConnection.request(this)
                }
            }
            activity.showSnackBar("Downloads initiated", "View", Snackbar.LENGTH_SHORT, null) {
                val intent = Intent(activity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
                activity.finish()
            }
        })
    }

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
            val txt = value.str
            setText(txt)
            setSelection(txt.length)
        }
}