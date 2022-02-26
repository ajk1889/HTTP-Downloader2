package resonance.http.httpdownloader.helpers.mainOptions

import android.annotation.SuppressLint
import android.text.Editable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import kotlin.collections.set
import kotlin.math.pow

class FilePartDownload(private val factory: QuestionFactory) : MainOption(factory.activity) {
    private companion object {
        const val OFFSET_TEXT = "offsetText"
        const val OFFSET_UNIT = "offsetUnit"
        const val LIMIT_TEXT = "limitText"
        const val LIMIT_UNIT = "limitUnit"
        const val DOWNLOAD_DATA = "downloadData"
    }
    private val tempStorage = factory.tempStorage
    private val parent = factory.parent
    private val reachedQuestions: MutableList<String>
        get() = factory.reachedQuestions

    private var total = 0L
    private var offset = 0L
        set(value) { field=value; updateDownloadVisual() }
    private var limit = -1L
        set(value) { field=value; updateDownloadVisual() }

    private var isDownloadVisualInitialized = false

    private lateinit var option4: Option
    override fun make(): Option = Option(
        activity = activity,
        option = "Download a <b>File part</b>",
        short = "file part",
        onInit = null,
        onClick = {
            it.isEnabled = false
            factory.question0.removeAllBut(3)
            mainQuestion().initialize().addTo(parent)
        }
    ).also { option4 = it }

    private lateinit var mainQuestion: Question
    private fun mainQuestion(): Question = Question(
        activity = activity,
        name = "filePartDownload",
        _description = "If your device's storage is low, try this option to download only a part of the whole file. " +
                "Make sure the size of the part fits in your available storage.",
        _question = "<b>Go to browser & click on your download link to continue</b>",
        onInit = null,
        onGoBack = { factory.reAdd(factory.question0, factory.question0()) },
        onGoForward = {
            if (reachedQuestions.contains("whichRange")) {
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
                whichRange().initialize().addTo(parent)
                reachedQuestions.contains("downloadDetails")
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
            whichRange()
        }.also { clickToOpenBrowser = it }
    }

    private lateinit var fromShared: Option
    private fun fromShared(): Option {
        return factory.fromShared {
            tempStorage[DOWNLOAD_DATA] = factory.downloadData
            response = null
            fromShared.isEnabled = false
            mainQuestion.removeAllBut(1)
            whichRange()
        }.also { fromShared = it }
    }

    private lateinit var whichRange: Question
    private fun whichRange(): Question = Question(
        activity = activity,
        name = "whichRange",
        _description = null,
        _question = "<b>Which range do you want to download?</b>",
        onInit = {},
        onGoBack = {
            copyParamsToTemp()
            factory.reAdd(mainQuestion, mainQuestion())
        },
        onGoForward = {
            if (reachedQuestions.contains("downloadDetails"))
                rangeContinue.click()
            false
        }
    ).apply {
        whichRange = this
        addOption(rangeSelector())
        addOption(downloadVisual())
        addOption(rangeContinue())
    }

    private lateinit var rangeSelector: Option
    @SuppressLint("InflateParams")
    private fun rangeSelector(): Option = Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = activity.layoutInflater.inflate(R.layout.range_selector,null)
            val offsetText = it.view.findViewById<EditText>(R.id.offset_text)
            val offsetSpinner = it.view.findViewById<Spinner>(R.id.offset_spinner)
            val limitText = it.view.findViewById<EditText>(R.id.limit_text)
            val limitSpinner = it.view.findViewById<Spinner>(R.id.limit_spinner)

            val adapter1 = object : ArrayAdapter<String>(activity, R.layout.spinner_item, arrayOf("GB", "MB", "KB", "bytes")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        setPadding(5.dp, 6.dp, 5.dp, 6.dp)
                    }
                }
            }
            offsetSpinner.adapter = adapter1
            offsetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    this@FilePartDownload.offset = (offsetInput * 1024.0.pow(3 - position)).toLong()
                }
            }
            limitSpinner.adapter = adapter1
            limitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val limit = limitInput
                    this@FilePartDownload.limit = if (limit > 0.0)
                        (limitInput * 1024.0.pow(3 - position)).toLong()
                    else total
                }
            }
            limitText.setOnTextChangedListener {
                val limit = limitInput
                when {
                    limit < 10 -> limitSpinner.setSelection(0)
                    limit < 1024 -> limitSpinner.setSelection(1)
                    else -> limitSpinner.setSelection(3)
                }
                this@FilePartDownload.limit = if (limit > 0L)
                    (limit * 1024.0.pow(3 - limitSpinner.selectedItemPosition)).toLong()
                else total
            }
            offsetText.setOnTextChangedListener {
                val offset = offsetInput
                when {
                    offset < 10 -> offsetSpinner.setSelection(0)
                    offset < 1024 -> offsetSpinner.setSelection(1)
                    else -> offsetSpinner.setSelection(3)
                }
                this@FilePartDownload.offset =
                    (offset * 1024.0.pow(3 - offsetSpinner.selectedItemPosition)).toLong()
            }

            offsetText.isEnabled = false; offsetSpinner.isEnabled = false
            limitText.isEnabled = false; limitSpinner.isEnabled = false

            runWithResponse(
                data = factory.downloadData,
                onSuccess = { response ->
                    total = response.total
                    offsetSpinner.isEnabled = true; limitSpinner.isEnabled = true
                    offsetText.isEnabled = true; limitText.isEnabled = true
                    if (tempStorage.containsKey(OFFSET_TEXT)) { //all other keys will definitely be there
                        offsetText.text = tempStorage[OFFSET_TEXT] as Editable
                        offsetSpinner.setSelection(tempStorage[OFFSET_UNIT] as Int)
                        limitText.text = tempStorage[LIMIT_TEXT] as Editable
                        limitSpinner.setSelection(tempStorage[LIMIT_UNIT] as Int)
                    } else {
                        offsetText.setText("0")
                        limitText.setText(total.str)
                    }
                    rangeContinue.isEnabled = true
                    rangeContinue.text = "Continue"
                    silently {
                        rangeContinue.view.findViewById<LinearLayout>(R.id.holder)
                            .removeView(progress)
                    }
                    updateDownloadVisual()
                },
                onError = { e ->
                    activity.showSnackBar(getExceptionCause(e,"FilePartDownload"))
                    rangeContinue.text = "<b><font color='#f2a983'>ERROR</font></b>"
                    silently {
                        rangeContinue.view.findViewById<LinearLayout>(R.id.holder)
                            .removeView(progress)
                    }
                }
            )
        },
        onClick = {},
        useCustomView = true
    ).also { rangeSelector = it }

    private lateinit var downloadVisual: Option
    private fun downloadVisual():Option = Option(
        activity = activity,
        option = "",
        onInit = {
            it.view = getDownloadVisual(activity,total, offset, limit)
            isDownloadVisualInitialized = true
            updateDownloadVisual()
        },
        onClick = {},
        useCustomView = true
    ).also {
        isDownloadVisualInitialized = false
        downloadVisual = it
    }

    private lateinit var rangeContinue: Option
    private var progress: ProgressBar? = null
    private fun rangeContinue():Option = Option(
        activity = activity,
        option = "Continue",
        short = null,
        onInit = {
            if (response != null) return@Option
            it.isEnabled = false
            it.disabledReason = "Please wait while we fetch the download details"
            it.text = "Loading..."
            progress = ProgressBar(activity).apply {
                layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 5.dp }
            }
            val holder = it.view.findViewById<LinearLayout>(R.id.holder)
            holder.addView(progress)
        },
        onClick = {
            if (!areWarningsShown(true)) {
                copyParamsToTemp()
                whichRange.removeAllBut(0)
                rangeSelector.view.findViewById<EditText>(R.id.offset_text).isEnabled = false
                rangeSelector.view.findViewById<Spinner>(R.id.offset_spinner).isEnabled = false
                rangeSelector.view.findViewById<EditText>(R.id.limit_text).isEnabled = false
                rangeSelector.view.findViewById<Spinner>(R.id.limit_spinner).isEnabled = false
                downloadDetails().initialize().addTo(parent)
            }
        }
    ).also { rangeContinue = it }

    private fun updateDownloadVisual(){
        if(!isDownloadVisualInitialized || areWarningsShown(false)) return

        if (offset != 0L)
            downloadVisual.view.findViewById<TextView>(R.id.text1).text = formatSize(offset)
        if (limit != 0L && limit != total)
            downloadVisual.view.findViewById<TextView>(R.id.text2).text = formatSize(limit)

        val w0 = offset * 1f / total
        val w2 = (total - (limit orElse total)) * 1f / total
        val w1 = 1 - w0 - w2
        downloadVisual.view.findViewById<View>(R.id.view0).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w0 }
        }
        downloadVisual.view.findViewById<View>(R.id.view1).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w1 }
        }
        downloadVisual.view.findViewById<View>(R.id.view2).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = w2 }
        }
    }

    private fun areWarningsShown(showWarningDirectly: Boolean): Boolean {
        if (response == null) return false
        val offsetWarning = rangeSelector.view.findViewById<ImageView>(R.id.offsetWarning)
        val limitWarning = rangeSelector.view.findViewById<ImageView>(R.id.limitWarning)
        if(offset >= total) {
            offsetWarning.unHide()
            offsetWarning.setOnClickListener { activity.showSnackBar(
                msg = "<b>Start from</b> (${formatSize(
                    offset,
                    3,
                    " "
                )}) should be less than total download size (${formatSize(total, 3, " ")})<br>" +
                        "Tip: Keeping <b>Start from</b> blank is same as <b>Start from</b> = beginning",
                duration = Snackbar.LENGTH_LONG
            ) }
            if (showWarningDirectly) offsetWarning.callOnClick()
        } else offsetWarning.setGone()
        if(offset >= limit){
            offsetWarning.unHide()
            offsetWarning.setOnClickListener { activity.showSnackBar(
                msg = "<b>Start from</b> (${formatSize(offset,3," ")}) should be less than <b>Stop at</b> (${formatSize(limit,3," ")})<br>" +
                        "Wan't to switch them?",
                btnTxt = "switch",
                duration = Snackbar.LENGTH_LONG,
                onClick = {
                    val offsetText = rangeSelector.view.findViewById<EditText>(R.id.offset_text).text
                    val offUnit = rangeSelector.view.findViewById<Spinner>(R.id.offset_spinner).selectedItemPosition
                    rangeSelector.view.findViewById<EditText>(R.id.offset_text).text =
                        rangeSelector.view.findViewById<EditText>(R.id.limit_text).text
                    rangeSelector.view.findViewById<Spinner>(R.id.offset_spinner).setSelection(
                        rangeSelector.view.findViewById<Spinner>(R.id.limit_spinner).selectedItemPosition
                    )
                    rangeSelector.view.findViewById<EditText>(R.id.limit_text).text = offsetText
                    rangeSelector.view.findViewById<Spinner>(R.id.limit_spinner).setSelection(offUnit)
                }
            ) }
            if (showWarningDirectly) offsetWarning.callOnClick()
        } else offsetWarning.setGone()

        if(limit > total){
            limitWarning.unHide()
            limitWarning.setOnClickListener { activity.showSnackBar(
                "<b>Stop at</b> (${formatSize(limit,3," ")}) should be less than total download size (${formatSize(total,3," ")})<br>" +
                        "Tip: Keeping <b>Stop at</b> blank is same as <b>Stop at</b> = File End"
            ) }
            if (showWarningDirectly) limitWarning.callOnClick()
        } else limitWarning.setGone()

        return offsetWarning.visibility == View.VISIBLE
                || limitWarning.visibility == View.VISIBLE
    }

    private lateinit var downloadDetails: Question
    private fun downloadDetails(): Question = Question(
        activity = activity,
        name = "downloadDetails",
        _description = null,
        _question = "<b>Here are some details about your download. Click on them to configure if you need</b>",
        onInit = {},
        onGoBack = { factory.reAdd(whichRange, whichRange()) },
        onGoForward = { false }
    ).apply {
        downloadDetails = this
        addOption(url())
        addOption(factory.totalSize("Full file size", this@FilePartDownload))
        addOption(factory.selectFolder("Folder", this@FilePartDownload))
        addOption(
            factory.fileName(
                option = "Name",
                fileNameSuffix = " (${formatSize(offset)}-${formatSize(limit orElse total)})",
                mainOption = this@FilePartDownload
            )
        )
        addOption(factory.emptyFirstModeOption())
        addOption(range())
        addOption(downloadVisual())
        addOption(factory.startDownload(this@FilePartDownload).apply {
            isEnabled = false
            disabledReason = "Please wait while we check whether file part is available"
            factory.downloadData = factory.downloadData.apply {
                //setting offset & limit
                put(C.dt.offset, offset)
                put(C.dt.limit, limit orElse total)
                runWithResponse( //to check file is available with given offset
                    data = this,
                    onSuccess = { isEnabled = true },
                    onError = {
                        isEnabled = false
                        disabledReason = getExceptionCause(it, "")
                        activity.showSnackBar(getExceptionCause(it, ""))
                    }
                )
            }
        })
    }

    private fun range(): Option = Option(
        activity = activity,
        option = "Download range: ${formatSize(offset,3," ")} to ${formatSize(limit orElse total,3," ")}",
        short = "download range",
        onInit = {
            it.isEnabled = false
            it.disabledReason = "Go back to modify download range"
        },
        onClick = null
    )

    private fun url(): Option = Option(
        activity = activity,
        option = "URL: <b>${factory.downloadData.getString(C.dt.url)}</b>",
        short = "downloadUrl",
        onInit = {
            it.isEnabled = false
            it.disabledReason = "You cannot change URL from here.<br>" +
                    "<font color=red><b>Go back to change url</b></font>"
        },
        onClick = null
    )

    private fun copyParamsToTemp() {
        tempStorage[OFFSET_TEXT] = rangeSelector.view.findViewById<EditText>(R.id.offset_text).text
        tempStorage[OFFSET_UNIT] =
            rangeSelector.view.findViewById<Spinner>(R.id.offset_spinner).selectedItemPosition
        tempStorage[LIMIT_TEXT] = rangeSelector.view.findViewById<EditText>(R.id.limit_text).text
        tempStorage[LIMIT_UNIT] =
            rangeSelector.view.findViewById<Spinner>(R.id.limit_spinner).selectedItemPosition
    }

    private val Int.dp: Int
        get() {
            val metrics = activity.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
    val offsetInput: Double
        get() = rangeSelector.view.findViewById<EditText>(R.id.offset_text).text.str.let {
            return@let if(it=="") 0.0 else it.toDouble()
        }
    val limitInput: Double
        get() = rangeSelector.view.findViewById<EditText>(R.id.limit_text).text.str.let {
            return@let if (it == "") 0.0 else it.toDouble()
        }
}