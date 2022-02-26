package resonance.http.httpdownloader.helpers.mainOptions

import org.json.JSONObject
import resonance.http.httpdownloader.core.DownloadObject
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.Option
import resonance.http.httpdownloader.helpers.Question
import resonance.http.httpdownloader.helpers.QuestionFactory

class SafelyDownloadAFile(private var factory: QuestionFactory) : MainOption(factory.activity) {
    private companion object {
        const val RESPONSE = "response"
        const val DOWNLOAD_DATA = "downloadData"
    }

    private val tempStorage = factory.tempStorage
    private val parent = factory.parent
    private val reachedQuestions: MutableList<String>
        get() = factory.reachedQuestions

    private lateinit var option1: Option
    override fun make() = Option(
        activity = activity,
        option = "Safely download a file",
        short = "downloadAFile",
        onInit = null,
        onClick = {
            it.isEnabled = false
            factory.question0.removeAllBut(0)
            mainQuestion().initialize().addTo(parent)
        },
        useCustomView = false
    ).also { option1 = it }

    private lateinit var mainQuestion: Question
    private fun mainQuestion(): Question = Question(
        activity = activity,
        name = "safelyDownloadAFile",
        _description = "This option helps you to download files over internet in the most fail-safe way. <i>If the download link expires, use <u><b>resume failed download</b></u> option to resume it.</i>",
        _question = "<b>Go to required website and click on download link to continue</b>",
        onInit = null,
        onGoBack = { factory.reAdd(factory.question0, factory.question0()) },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails")) {
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
                downloadDetails().initialize().addTo(parent)
            }
            false
        }
    ).apply {
        addOption(clickToOpenBrowser())
        addOption(fromShared())
        mainQuestion = this
    }

    private lateinit var clickToOpenBrowser: Option
    private fun clickToOpenBrowser(): Option {
        return factory.clickToOpenBrowser {
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            response?.also { tempStorage[RESPONSE] = it }
            response = null
            clickToOpenBrowser.isEnabled = false
            mainQuestion.removeAllBut(0)
            downloadDetails()
        }.apply { clickToOpenBrowser = this }
    }

    private lateinit var fromShared: Option
    private fun fromShared(): Option {
        return factory.fromShared {
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            response?.also { tempStorage[RESPONSE] = it }
            response = null
            fromShared.isEnabled = false
            mainQuestion.removeAllBut(1)
            downloadDetails()
        }.apply { fromShared = this }
    }

    private lateinit var downloadDetails: Question
    private fun downloadDetails(): Question = Question(
        activity = activity,
        name = "downloadDetails",
        _description = "Here are some details about your download",
        _question = "<b>Click on them to configure if you need</b>",
        onInit = null,
        onGoBack = { factory.reAdd(mainQuestion, mainQuestion()) }
    ).apply {
        addOption(url())
        addOption(factory.selectFolder(mainOption = this@SafelyDownloadAFile))
        addOption(factory.fileName(mainOption = this@SafelyDownloadAFile))
        addOption(factory.totalSize(mainOption = this@SafelyDownloadAFile))
        addOption(factory.emptyFirstModeOption())
        addOption(factory.startDownload(this@SafelyDownloadAFile))
        downloadDetails = this
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
}