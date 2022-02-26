package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.Transformation
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_file_joiner.*
import org.json.JSONArray
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.adapters.FileItemAdapter
import resonance.http.httpdownloader.broadcastReceivers.FileJoinerReceiver
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.UriFile
import resonance.http.httpdownloader.services.JoinerService

@SuppressLint("SetTextI18n")
class FileJoiner : ParentActivity() {
    companion object {
        const val SELECT_PARTS = 5216
        const val SELECT_OUTPUT = 2561
        const val ANIM_DURATION = 200L/*ms*/
    }

    var failedOnce = false
    private val anim = AnimFactory(ANIM_DURATION)
    private val receiver = FileJoinerReceiver(this)
    private val adapter: FileItemAdapter by lazy {
        FileItemAdapter(this, files)
    }
    private val broadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    val files = mutableListOf<UriFile>()
    var outputFile: UriFile? = null
    var from = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("FileJoiner", "onCreate: called")
        setContentView(R.layout.activity_file_joiner)
        from = intent?.getStringExtra(C.misc.from) ?: "MainActivity"

        list.adapter = adapter
        deleteAfterJoin.setOnCheckedChangeListener { _, isChecked ->
            log("FileJoiner", "oCr: deleteAfterJoin: checked=$isChecked")
            updateStorageInfo()
        }
        appendToFirst.setOnCheckedChangeListener { _, isChecked ->
            log("FileJoiner", "oCr: appendToFirst: checked=$isChecked")
            if (isChecked) {
                if (files.size < 2) {
                    showSnackBar("Require at least 2 file parts to join")
                    appendToFirst.isChecked = false
                    selectOpFile.enable()
                    startJoinBtn.disable()
                } else {
                    selectOpFile.disable()
                    startJoinBtn.enable()
                    opName.text = "Output file name: <b>${files[0].name.shorten()}</b>".asHtml()
                    log("FileJoiner", "onCreate: appendToFirst: outputFileName set")
                }
            } else {
                log("FileJoiner", "onCreate: appendToFirst: ${outputFile.nuLliTy()}")
                selectOpFile.enable()
                if (outputFile == null) {
                    startJoinBtn.disable()
                    opName.setText(R.string.output_file_name)
                } else {
                    startJoinBtn.enable()
                    opName.text = "Output file name: <b>${outputFile?.name?.shorten()}</b>".asHtml()
                }
            }
            updateStorageInfo()
        }
    }

    override fun onPause() {
        log("FileJoiner", "onPause: called")
        broadcastManager.unregisterReceiver(receiver)
        super.onPause()
    }

    override fun onResume() {
        log("FileJoiner", "onResume: called")
        if (JoinerService.isRunning) {
            if (isCollapsed[R.id.title1] == false) toggle(title1)
            if (isCollapsed[R.id.title2] == false) toggle(title2)
            if (isCollapsed[R.id.title3] == true) toggle(title3)
        }
        val filter = IntentFilter(C.filter.JOINER_PROGRESS)
        filter.addAction(C.filter.JOINING_FAILED)
        broadcastManager.registerReceiver(receiver, filter)
        JoinerService.lastBroadcast?.also { broadcastManager.sendBroadcast(it) }
        super.onResume()
    }

    private var isCollapsed = mutableMapOf(
        R.id.title1 to false,
        R.id.title2 to true,
        R.id.title3 to true
    )

    var isManuallyToggled = false
    fun toggle(view: View) {
        fun calculateExpandedHeight(parent: View, body: View): Int {
            parent.measure(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            return if (body.visibility == View.GONE) {
                body.measure(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                parent.measuredHeight + body.measuredHeight
            } else parent.measuredHeight
        }

        val title: View
        val holder: View
        val expandedHeight: Int
        val upDownIco: ImageView
        val contents: View

        when (view.id) {
            R.id.title1 -> {
                title = title1; holder = holder1
                contents = contents1; upDownIco = upDownIco1
                expandedHeight = 400.dp
                log("FileJoiner", "toggle: title1 current state=" + contents.visibility)
            }
            R.id.title2 -> {
                title = title2; holder = holder2
                contents = contents2; upDownIco = upDownIco2
                expandedHeight = calculateExpandedHeight(holder, contents)
                log("FileJoiner", "toggle: title2 current state=" + contents.visibility)
            }
            else -> {
                isManuallyToggled = true
                title = title3; holder = holder3
                contents = contents3; upDownIco = upDownIco3
                expandedHeight = calculateExpandedHeight(holder, contents)
                log("FileJoiner", "toggle: title3 current state=" + contents.visibility)
            }
        }

        fun toggleAnim(from: Int, to: Int, onEnd: (() -> Unit)? = null): Animation {
            val anim = object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                    val height = (to - from) * interpolatedTime + from
                    holder.layoutParams = holder.layoutParams.apply { this.height = height.toInt() }
                    holder.requestLayout()
                    parentView.smoothScrollTo(holder.x.toInt(), holder.y.toInt())
                }
            }.apply {
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationRepeat(animation: Animation?) {}
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        onEnd?.invoke()
                    }
                })
            }
            anim.duration = ANIM_DURATION
            return anim
        }

        val isHidden = isCollapsed[view.id] ?: return
        log("FileJoiner", "toggle: isHidden=$isHidden")

        title.measure(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val collapsedHeight = title.measuredHeight + (3 * 2).dp /*padding*/

        val toggleAnim: Animation
        val rotateAnim: Animation
        if (isHidden) {
            contents.unHide()
            rotateAnim = anim.rotateIcon(upDownIco, 0, 180)
            toggleAnim = toggleAnim(collapsedHeight, expandedHeight)
        } else {
            rotateAnim = anim.rotateIcon(upDownIco, 180, 0)
            toggleAnim = toggleAnim(expandedHeight, collapsedHeight) {
                contents.setGone()
            }
        }

        rotateAnim.duration = ANIM_DURATION
        toggleAnim.duration = ANIM_DURATION

        holder.startAnimation(toggleAnim)
        upDownIco.startAnimation(rotateAnim)
        isCollapsed[view.id] = !isHidden
        log("FileJoiner", "toggle: completed")
    }

    fun help(view: View) {
        log("FileJoiner", "help: clicked help button")
        Browser.start(
            context = this,
            from = Browser.Companion.FromOptions.FILE_JOINER,
            url = "https://resonance00x0.github.io/http-downloader/file-joiner",
            request = C.misc.startDownload
        )
    }

    fun selectFiles(view: View) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        log("FileJoiner", "selectFiles: startingActivityForResult code=$SELECT_PARTS")
        startActivityForResult(Intent.createChooser(intent, "Select file parts"), SELECT_PARTS)
    }

    fun updateStorageInfo() {
        log(
            "FileJoiner",
            "updateStorageInfo: files.size=" + files.size,
            "deleteAfterJoin.isChecked=" + deleteAfterJoin.isChecked,
            "appendToFirst.isChecked=" + appendToFirst.isChecked
        )
        if (files.size < 2) { // can't join a single or no file
            totalSize.text = "File size after joining: <b>---<b>".asHtml()
            spaceRequired.text = "Extra space required for joining: <b>---<b>".asHtml()
            return
        }
        var joinedSize = 0L
        files.forEach { joinedSize += it.size }
        totalSize.text = ("File size after joining: " +
                "<b>${formatSize(joinedSize, 3, " ")}<b>").asHtml()
        log(
            "FileJoiner",
            "updateStorageInfo: fileSizeAfterJoining=$joinedSize = ",
            formatSize(joinedSize, 3, " ")
        )

        if (deleteAfterJoin.isChecked) {
            var maxSize = -1L // ignore 1 byte error (to show --- in case of blank)
            if (appendToFirst.isChecked) {
                for (i in 1 until files.size) {
                    if (files[i].size > maxSize)
                        maxSize = files[i].size
                }
            } else files.forEach { if (it.size > maxSize) maxSize = it.size }

            spaceRequired.text = ("Extra space required for joining: " +
                    "<b>${formatSize(maxSize, 3, " ")}</b>").asHtml()
            log(
                "FileJoiner",
                "updateStorageInfo: extra space required for joining = $maxSize = ",
                formatSize(maxSize, 3, " ")
            )
        } else {
            val extra = if (appendToFirst.isChecked)
                joinedSize - files[0].size
            else joinedSize
            spaceRequired.text = ("Extra space required for joining: " +
                    "<b>${formatSize(extra, 3, " ")}</b>").asHtml()
            log(
                "FileJoiner",
                "updateStorageInfo: extra space required for joining = $extra = ",
                formatSize(extra, 3, " ")
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        log(
            "FileJoiner", "onActivityResult: ",
            "requestCode=$requestCode",
            "resultCode=$resultCode",
            "data.data=" + data?.data.nuLliTy(),
            "data.clipData.count=" + data?.clipData?.itemCount,
            "data.extras=" + data?.extras?.keySet()?.toList()
        )
        if (requestCode == SELECT_PARTS) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    val tempList = MutableList(clipData.itemCount) {
                        UriFile(this, clipData.getItemAt(it).uri.str)
                    }
                    if (Pref.autoSortFileJoiner)
                        tempList.sortBy { it.getComparable() }
                    files.addAll(tempList)
                    adapter.notifyDataSetChanged()
                    log(
                        "FileJoiner",
                        "onActivityResult: added all items",
                        tempList.size,
                        "to adapter, count=",
                        adapter.count
                    )
                } else data.data?.also {
                    val uriFile = { UriFile(this, it.str) } otherwise null
                    if (uriFile != null) adapter.add(uriFile)
                    else showSnackBar("Could not access this file")
                } ?: showSnackBar("Invalid data received")
            } else showSnackBar("No file selected")
        } else if (requestCode == SELECT_OUTPUT) {
            if (resultCode == Activity.RESULT_OK) {
                data?.data?.also {
                    contentResolver.takePersistableUriPermission(it, intent.getRWFlags())
                    val op = { UriFile(this, it.str) } otherwise null
                    if (op == null) {
                        showSnackBar("Could not access this file"); return
                    }
                    log("FileJoiner", "onActivityResult: outputFile.size = ", op.size)
                    if (op.size > 0) {
                        AlertDialog.Builder(this)
                            .setTitle("Non-Empty file")
                            .setMessage(
                                """The selected file does not seem to be empty.<br>
                                |<b>Its contents will be overwritten while joining.</b>"""
                                    .trimMargin().asHtml()
                            )
                            .setPositiveButton("Continue") { d, _ -> d.dismiss() }
                            .setNegativeButton("Choose another") { d, _ ->
                                log("FileJoiner", "onActivityResult: choose another clicked")
                                startJoinBtn.disable()
                                opName.setText(R.string.output_file_name)
                                outputFile = null
                                selectOpFile.callOnClick()
                                d.dismiss()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    selectOpFile.enable()
                    if (files.size > 1) startJoinBtn.enable()
                    opName.text = "Output file name: <b>${op.name.shorten()}</b>".asHtml()
                    outputFile = op
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private var verified = false
    fun continue1(view: View) {
        if (contents2.visibility == View.GONE) {
            log("FileJoiner", "continue1: Contents2 is GONE")
            if (!verified) {
                showSnackBar(
                    msg = "<b>Double check the order of files.</b> If you join them in wrong order," +
                            " <font color='#FF8157'>file will get corrupted</font>",
                    duration = Snackbar.LENGTH_INDEFINITE
                )
                verified = true
            } else {
                dismissSnackBar()
                toggle(title2)
                toggle(title1)
            }
        } else toggle(title1)
    }

    fun showInfo(view: View) {
        val message = when (view.id) {
            R.id.deleteAfterJoinInfo -> "File part will be deleted as soon as it is joined.<br/>" +
                    "So joining process takes much less extra space during the process"
            R.id.appendToFirstInfo -> "Remaining parts will be written on the top of 1st part.<br/>" +
                    "So, for huge files, joining will complete much faster"
            else -> "At least this amount of free space should be remaining on the output disk" +
                    " for file joining to complete successfully"
        }
        showSnackBar(msg = message, duration = Snackbar.LENGTH_INDEFINITE)
    }

    fun startJoin(view: View) {
        log("FileJoiner", "startJoin: called")
        if (preJoinCheckFailed()) return

        log("FileJoiner", "startJoin: contents3.visibility=", contents3.visibility)
        if (contents3.visibility == View.GONE) {
            dismissSnackBar()
            toggle(title3)
        } else parentView.smoothScrollTo(holder3.x.toInt(), holder3.y.toInt())

        disableAll()
        failedOnce = false
        startService(Intent(this, JoinerService::class.java).apply {
            val data = JSONObject()
            val inputs = Array(files.size) { files[it].uri.str }
            log("FileJoiner", "startJoin: inputs.size=", inputs.size)
            data.put(C.joiner.inputs, JSONArray(inputs))
            data.put(
                C.joiner.output,
                if (appendToFirst.isChecked) C.joiner.first
                else outputFile!!.uri.str
            )
            data.put(C.dt.type, JoinerObject.TYPE)
            data.put(C.joiner.deleteAfterJoin, deleteAfterJoin.isChecked)
            putExtra(C.misc.fileJoinerDataJSON, data.str)
        })
    }

    private var ignore4GbWarning = false
    private fun preJoinCheckFailed(): Boolean {
        if (files.size < 2) {
            showSnackBar("At least 2 parts are required for joining")
            return true
        }
        if (outputFile == null && !appendToFirst.isChecked) {
            showSnackBar("No output file specified")
            return true
        }
        if (!ignore4GbWarning && Pref.isFAT32Probable && files.totalSize() >= 4.GB) {
            var msg = """The combined size of files is <b>${formatSize(files.totalSize())}</b>.<br/>
                    |But <b>one of your storage media</b> does not support 4GB+ files 
                    |<i>(we don't know which one)</i><br/>
                    |If you are joining to <b>that</b> folder, the process may fail in the midway<br/>
                    |<br/>""".trimMargin()
            msg += when {
                appendToFirst.isChecked && deleteAfterJoin.isChecked ->
                    """<font color=red>
                        |You've selected <b>Append to first file & Delete after joining</b>; 
                        |if joining is interrupted, you will need to re-download the lost files.
                        |</font><br/>""".trimMargin()
                appendToFirst.isChecked ->
                    """<font color=red>
                        |You've selected <b>append to first file</b>; 
                        |if joining is interrupted, you will need to re-download 1st part.
                        |</font><br/>""".trimMargin()
                deleteAfterJoin.isChecked ->
                    """<font color=red>
                        |You've selected <b>Delete after joining</b>; 
                        |if joining is interrupted, you will need to re-download deleted parts.
                        |</font><br/>""".trimMargin()
                else -> ""
            }
            msg += "<b>Are you sure to continue?</b>"
            AlertDialog.Builder(this)
                .setTitle("Huge file")
                .setMessage(msg.asHtml())
                .setPositiveButton("Continue") { d, _ ->
                    log("FileJoiner", "preJoinCheckFailed: huge file alert: continue clicked")
                    ignore4GbWarning = true
                    startJoinBtn.callOnClick()
                    d.dismiss()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .show()
            log("FileJoiner", "preJoinCheckFailed: Huge file alertDialog shown")
            return true
        }
        getUnavailableInputs().also {
            if (it.isNotEmpty()) {
                log("FileJoiner", "preJoinCheckFailed: " + it.size + " inputFiles unavailable")
                val msg = "The following list of files aren't accessible now.<br>" +
                        "<b>Please re-select them to continue joining</b><br>" +
                        "<br>" + it.toListItems()
                AlertDialog.Builder(this)
                    .setTitle("Can't access files")
                    .setMessage(msg.asHtml())
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .show()
                return true
            }
        }
        log("FileJoiner", "preJoinCheckFailed: did not failed; returning false")
        return false
    }

    private fun getUnavailableInputs(): MutableList<UriFile> {
        val list = mutableListOf<UriFile>()
        for (file in files) {
            try {
                file.reInitialize()
            } catch (_: Exception) {
                list.add(file)
            }
        }
        log("FileJoiner", "getUnavailableInputs: count=", list.size)
        return list
    }

    fun disableAll() {
        log("FileJoiner", "disableAll: called")
        continueBtn1.disable()
        startJoinBtn.disable()
        selectOpFile.disable()
        selectIpFiles.disable()
        appendToFirst.isEnabled = false
        deleteAfterJoin.isEnabled = false
        adapter.isEnabled = false
        cancelBtn.unHide()
    }

    fun enableAll() {
        log("FileJoiner", "enableAll: called")
        continueBtn1.enable()
        startJoinBtn.enable()
        selectOpFile.enable()
        selectIpFiles.enable()
        appendToFirst.isEnabled = true
        deleteAfterJoin.isEnabled = true
        adapter.isEnabled = true
        cancelBtn.hide()
    }

    fun selectOutputFile(view: View) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, "")
        }
        log("FileJoiner", "selectOutputFile: called code=$SELECT_OUTPUT")
        startActivityForResult(intent, SELECT_OUTPUT)
    }

    override fun onBackPressed() {
        when {
            snackBar != null -> dismissSnackBar()
            JoinerService.isRunning -> {
                log("FileJoiner", "onBackPressed: Showing Join in background alert")
                AlertDialog.Builder(this)
                    .setTitle("Join in background?")
                    .setMessage(
                        """Do you want the joining process to run in background?<br>
                            |<br><b><u>Hint</b></u>:<br>
                            |<b>YES</b>: Join from background & close app<br>
                            |<b>NO</b>: Stop joining & exit app<br>
                            |<b>CANCEL</b>: Do nothing; close this dialog
                        """.trimMargin().asHtml()
                    )
                    .setPositiveButton("Yes") { d, _ ->
                        log("FileJoiner", "onBackPressed: join in background? yes")
                        d.dismiss()
                        exit(shouldFinish = false)
                    }
                    .setNegativeButton("No") { d, _ ->
                        log("FileJoiner", "onBackPressed: join in background? no")
                        d.dismiss()
                        cancelJoining { exit(shouldFinish = true) }
                    }
                    .setNeutralButton("Cancel") { d, _ ->
                        log("FileJoiner", "onBackPressed: join in background? cancel")
                        d.dismiss()
                    }
                    .setCancelable(true)
                    .show()
            }
            else -> {
                log("FileJoiner", "onBackPressed: super.onBackPressed")
                exit(shouldFinish = joiningComplete || failedOnce)
            }
        }
    }

    private fun exit(shouldFinish: Boolean) {
        val intent = if (from == "MainActivity")
            Intent(this, MainActivity::class.java)
        else Intent(this, SimpleActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        moveTaskToBack(false)
        if (shouldFinish) super.onBackPressed()
    }

    fun cancelJoining(view: View) = cancelJoining { enableAll() }

    private fun cancelJoining(onStopJoining: () -> Unit) {
        log(
            "FileJoiner",
            "cancelJoining:",
            "appendToFirst=" + appendToFirst.isChecked,
            "deleteAfterJoin=" + deleteAfterJoin.isChecked,
            "currentPart=$currentPart"
        )
        val msg = when {
            appendToFirst.isChecked && deleteAfterJoin.isChecked && currentPart > 0 ->
                """<font color=red>It is dangerous to stop joining when <b>Append to first file AND Delete files after joining</b> were enabled</font><br>
                    |1st part will become corrupted if you interrupt joining process.<br><br>
                    |Also the first $currentPart parts were deleted during the process.<br><br>
                    |<b>Proceed only if you have a backup of the first ${if (currentPart == 1) "part" else "$currentPart parts"} on some other location</b>
                """.trimMargin()
            deleteAfterJoin.isChecked && currentPart > 0 ->
                """<font color=red>It is dangerous to stop joining when <b>Delete files after joining</b> was enabled</font><br>
                    |The first first ${if (currentPart == 1) "part was" else "$currentPart parts were"} deleted during the process.<br><br>
                    |<b>Proceed only if you have a backup of the deleted parts on some other location</b>
                """.trimMargin()
            appendToFirst.isChecked ->
                """<font color=red>It is dangerous to stop joining when <b>Append to first file</b> was enabled</font><br>
                    |first part will become corrupted if you interrupt joining process.<br><br>
                    |<b>Proceed only if you have a backup of the 1st part on some other location</b>
                """.trimMargin()
            else -> "Do you want to <b>Stop</b> the joining process?"
        }
        AlertDialog.Builder(this)
            .setCancelable(true)
            .setTitle("Are you sure?")
            .setMessage(msg.asHtml())
            .setPositiveButton("Stop joining") { d, _ ->
                log("FileJoiner", "cancelJoining: stop joining button clicked")
                d.dismiss()
                stopService(Intent(this, JoinerService::class.java))
                onStopJoining()
            }
            .setNegativeButton("Cancel") { d, _ ->
                log("FileJoiner", "cancelJoining: cancel button clicked")
                d.dismiss()
            }
            .show()
    }

    // in case of appendToFirst, service will handle incrementing currentPart
    private var currentPart = 0
    private var joiningComplete = false
    fun updateProgress(intent: Intent) {
        totalReach.text = formatSize(intent.getLongExtra(C.joiner.totalReach, -1), 1) + "/" +
                formatSize(intent.getLongExtra(C.joiner.totalSize, 0), 1)
        currentPart = intent.getIntExtra(C.joiner.currentPart, 0)

        val remoteFiles = JoinerService.files
        val localFiles = files
        val name = when {
            localFiles.size > currentPart -> localFiles[currentPart].name.shorten()
            remoteFiles.size > currentPart -> {
                if (appendToFirst.isChecked) {
                    { remoteFiles[currentPart - 1].name.shorten() } otherwise "..."
                } else remoteFiles[currentPart].name.shorten()
            }
            else -> "...."
        }
        currentFile.text = "Current part: ${currentPart + 1} (<b>$name</b>)".asHtml()

        intent.getLongExtra(C.joiner.totalProgress, 0).also {
            totalProgress.text = "$it%"
            totalProgressBar.progress = it.toInt()
            if (it == 100L) {
                log(
                    "FileJoiner",
                    "updateProgress: joining complete; joined",
                    "${currentPart + 1} parts"
                )
                currentFile.text = "<b>Joined ${currentPart + 1} parts successfully</b>".asHtml()
                joiningComplete = true
                enableAll()
            }
        }
        speed.text = intent.getStringExtra(C.joiner.speed)
        partReach.text = formatSize(intent.getLongExtra(C.joiner.currentReach, -1), 1) + "/" +
                formatSize(intent.getLongExtra(C.joiner.currentPartSize, 0), 1)
        intent.getLongExtra(C.joiner.partProgress, 0).also {
            partProgress.text = "$it%"
            partProgressBar.progress = it.toInt()
        }
    }

    private fun MutableList<UriFile>.toListItems(): String {
        val builder = StringBuilder()
        for (file in this) {
            builder.append("<b>•</b> <i>")
                .append(file.name)
                .append("</i> <b>(part-")
                .append(files.indexOf(file) + 1)
                .append(")</b><br>")
        }
        return builder.delete(builder.length - 4, builder.length).str
    }

    fun MutableList<UriFile>.totalSize(): Long {
        var total = 0L
        for (file in this) total += file.size
        return total
    }
}
