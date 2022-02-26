package resonance.http.httpdownloader.adapters

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.TransferWrapper
import resonance.http.httpdownloader.services.TransferService
import resonance.http.httpdownloader.views.TimePickerDialog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

open class DownloadsListAdapter(
    private val activity: MainActivity
) : ArrayAdapter<TransferWrapper>(activity.applicationContext, R.layout.download_list_main) {

    private val heightCollapsed = 110.dp
    private val heightExpanded = 208.dp
    private val animFactory = AnimFactory()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: activity.layoutInflater
            .inflate(R.layout.download_list_main, parent, false)
        val item = getItem(position)!!

        setViewProperties(view, item)
        setOnClickListeners(view, item)
        return view
    }

    override fun notifyDataSetChanged() {
        /*Normally ShouldAnimateBtns is changed to false only after animation is completed
          If animation didn't finish before recreating layout, animation restarts from beginning.
          To avoid it stop animation ('shouldAnimateBtns = false') if it has started already (elapsed 50ms)*/
        for (i in 0 until count) with(getItem(i) ?: continue) {
            if (shouldAnimateBtns && now() > animateBtnsSetTime + 50)
                shouldAnimateBtns = false
        }
        super.notifyDataSetChanged()
    }

    override fun isEnabled(position: Int): Boolean {
        return false
    }

    fun getTaskById(id: Long): TransferWrapper? {
        for (i in 0 until count) {
            val item = getItem(i)
            if (item?.id == id) return item
        }
        return null
    }

    private fun setOnClickListeners(view: View, item: TransferWrapper) {
        view.setOnClickListener { toggle(view, item) }
        view.findViewById<View>(R.id.downloadSettings)
            .setOnClickListener { showDownloadSettings(item) }
        view.findViewById<View>(R.id.pause).setOnClickListener { pause(it, item) }
        view.findViewById<View>(R.id.stop).setOnClickListener { stop(it, item) }
        view.findViewById<View>(R.id.delete).setOnClickListener { delete(view, item) }
        view.findViewById<View>(R.id.status).setOnClickListener { showStatus(view, item) }
        view.findViewById<View>(R.id.edit).setOnClickListener { edit(item) }
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showDownloadSettings(item: TransferWrapper) {
        val view = activity.layoutInflater.inflate(R.layout.download_settings_dialog, null)
        fun configureSpeedLimit() {
            val limitSpeed = view.findViewById<View>(R.id.limitSpeed)
            limitSpeed.setOnClickListener {
                activity.dialog?.dismiss(); limitSpeed(item)
            }
            val txt = view.findViewById<TextView>(R.id.limitSpeedTxt)
            val ico = view.findViewById<ImageView>(R.id.limitSpeedIco)
            if (item.maxSpeed == Int.MAX_VALUE) {
                txt.setTextColor(Color.BLACK)
                txt.setText(R.string.limit_download_speed)
                ico.setImageResource(R.drawable.speed)
            } else {
                txt.setTextColor(ContextCompat.getColor(activity, R.color.colorAccent_light))
                txt.text = "Download speed limit: ${formatSize(item.maxSpeed.toLong())}/s"
                ico.setImageResource(R.drawable.speed_limited)
            }
        }

        fun configureSchedule() {
            val scheduleDownload = view.findViewById<View>(R.id.scheduleDownload)
            scheduleDownload.setOnClickListener {
                activity.dialog?.dismiss(); scheduleDownload(item)
            }
            val txt = view.findViewById<TextView>(R.id.scheduleText)
            val ico = view.findViewById<ImageView>(R.id.scheduleIco)
            val now = now()
            if (item.scheduledTime < now) {
                txt.setTextColor(Color.BLACK)
                txt.setText(R.string.schedule_download)
                ico.setImageResource(R.drawable.schedule_black)
            } else {
                txt.setTextColor(ContextCompat.getColor(activity, R.color.colorAccent_light))
                txt.text = "Start at: " + TimePickerDialog.toHourAndMinute(item.scheduledTime)
                ico.setImageResource(R.drawable.schedule_blue)
            }
        }

        fun configureStreamingServer() {
            val streamServer = view.findViewById<View>(R.id.streamServer)
            streamServer.setOnClickListener {
                activity.dialog?.dismiss(); setStreamServer(item)
            }
            if (item.isVideoOrAudio && !item.hasSucceeded && !item.contentLengthType) {
                streamServer.unHide()
                val txt = view.findViewById<TextView>(R.id.streamServerTxt)
                val ico = view.findViewById<ImageView>(R.id.streamServerIco)
                if (item.isStreamServer) {
                    txt.setTextColor(ContextCompat.getColor(activity, R.color.colorAccent_light))
                    ico.setImageResource(R.drawable.stream_server_true)
                } else {
                    txt.setTextColor(Color.BLACK)
                    ico.setImageResource(R.drawable.stream_server_false)
                }
            } else streamServer.setGone()
        }

        fun configureShareDialog() {
            view.findViewById<View>(R.id.share).setOnClickListener {
                activity.dialog?.dismiss(); share(item)
            }
        }

        configureSchedule()
        configureShareDialog()
        configureSpeedLimit()
        configureStreamingServer()
        activity.dialog = AlertDialog.Builder(activity)
            .setView(view)
            .show()
    }

    private fun limitSpeed(item: TransferWrapper) = activity.showSpeedLimitDialog(
        initialSpeed = item.maxSpeed,
        maxSpeedText = "Use speed limit on Settings",
        onSpeedUpdated = {
            if (TransferService.isRunning) {
                val intent = Intent(C.filter.REQUEST)
                intent.putExtra(C.misc.request, C.req.limitSpeed)
                intent.putExtra(C.dt.id, item.id)
                intent.putExtra(C.dt.maxSpeed, it)
                activity.transferServiceConnection.request(intent)
            } else silently {
                item.maxSpeed = it
                TransferWrapper.loadTransfer(activity, item.id).apply {
                    maxSpeed = it; saveState(activity)
                }
            }
        }
    )

    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Sends local changes of this TransferWrapper to TransferService
     */
    private fun syncState(item: TransferWrapper, keys: MutableSet<String>) {
        val json = item.getAbsoluteJson()
        val changes = JSONObject()
        for (key in keys) changes.put(key, json[key])

        if (TransferService.isRunning) {
            val syncRequest = Intent(C.filter.REQUEST)
            syncRequest.putExtra(C.misc.request, C.req.syncData)
            syncRequest.putExtra(C.dt.id, item.id)
            syncRequest.putExtra(C.misc.task, changes.toString())
            activity.transferServiceConnection.request(syncRequest)
        } else {
            singleThreadExecutor.submit {
                silently {
                    TransferWrapper.loadTransfer(activity, item.id).apply {
                        copyContentsFrom(changes)
                        saveState(activity)
                    }
                }
            }
        }
    }

    private fun scheduleDownload(item: TransferWrapper) {
        if (!Pref.scheduleDownloadIntroShown) {
            val message = """This feature lets you automatically start downloads at a specified time
                |<br>
                |<br><u><b>Notes</b></u>
                |<br><b>Some websites may not support this</b>
                |<br>Some websites expire the download link when it is not accessed for long time.
                |In such cases, the downloads will fail immediately after starting.
                |<br>
                |<br><b>This may not work if:</b> 
                |<br> - power saver mode is ON, 
                |<br> - device is switched off,
                |<br> - network is disconnected
                |<br> - and more such acceptable conditions
            """.trimMargin().asHtml()
            activity.dialog = AlertDialog.Builder(activity)
                .setTitle("Download scheduling")
                .setMessage(message)
                .setPositiveButton("OK") { d, _ ->
                    Pref.scheduleDownloadIntroShown = true
                    d.dismiss()
                    scheduleDownload(item)
                }
                .setNegativeButton("Dismiss") { d, _ -> d.dismiss() }
                .show()
            return
        }
        if (item.hasSucceeded) {
            activity.showSnackBar("Download already completed")
            return
        }
        if (!item.isStopped && !item.isPaused) return activity.showSnackBar(
            "This download is ongoing. Please pause/stop before scheduling it"
        )

        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val initTime = if (item.scheduledTime < now()) now() + 24.hours else item.scheduledTime
        TimePickerDialog(
            activity = activity,
            initTime = initTime,
            onTimeSelected = { selectedTime ->
                log("DLA", "scheduleDownload: $selectedTime")
                item.scheduledTime = selectedTime
                if (selectedTime > now()) item.statusIcon = C.ico.scheduled
                notifyDataSetChanged()
                syncState(item, mutableSetOf(C.dt.scheduledTime, C.dt.statusIcon))
                AlarmManagerCompat.setAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.RTC_WAKEUP,
                    selectedTime,
                    getSchedulerPendingIntent(selectedTime)
                )
            },
            onScheduleRemoved = {
                log("DLA", "ScheduleRemoved")
                item.scheduledTime = -1
                item.statusIcon = C.ico.blank
                notifyDataSetChanged()
                syncState(item, mutableSetOf(C.dt.scheduledTime, C.dt.statusIcon))
                notifyDataSetChanged()
            }
        ).show()
    }

    private fun cancelScheduler(item: TransferWrapper) {
        if (item.scheduledTime > now())
            activity.showSnackBar("Scheduling is cancelled for this task")
        item.scheduledTime = -1
        if (item.statusIcon == C.ico.scheduled)
            item.statusIcon = C.ico.blank
        notifyDataSetChanged()
        syncState(item, mutableSetOf(C.dt.scheduledTime, C.dt.statusIcon))
    }

    private fun getSchedulerPendingIntent(scheduledTime: Long): PendingIntent {
        val resumeRequest = Intent(C.filter.runScheduledTasks)
        val requestCode = 2 + (scheduledTime % 1000).toInt()
        return PendingIntent.getBroadcast(
            activity,
            requestCode,
            resumeRequest,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStreamServer(item: TransferWrapper) {
        fun onOk() {
            val intent = Intent(C.filter.REQUEST)
            intent.putExtra(C.misc.request, C.req.markStreamServer)
            intent.putExtra(C.dt.id, item.id)
            intent.putExtra(C.dt.isStreamServer, !item.isStreamServer)
            activity.transferServiceConnection.request(intent)
            Pref.streamModeDialogShown = true
        }
        if (Pref.streamModeDialogShown) {
            onOk(); return
        }
        val enableOrDisable = if (item.isStreamServer) "disable" else "enable"
        activity.dialog = AlertDialog.Builder(activity)
            .setTitle("Turn on continuous refresh?")
            .setMessage(
                """Some websites give high speeds at beginning of download & reduces speed after some time.
                |The high speed can be maintained by continuously refreshing the download.<br>
                |<br>
                |<b><u>Note:</u></b><br>
                |1. Enabling this may reduce speed for some downloads.<br>
                |2. The refresh interval can be adjusted from app's settings<br>
                |<br>
                |<b>Do you want to $enableOrDisable continuous refresh for this download?</b>"""
                    .trimMargin().asHtml()
            )
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton("Cancel") { _, _ -> }
            .setNeutralButton("read more") { _, _ ->
                Browser.start(
                    context = activity,
                    from = Browser.Companion.FromOptions.MAIN,
                    url = "https://resonance00x0.github.io/http-downloader/faq#what-is-continuous-reconnect-mode",
                    request = C.misc.startDownload
                )
            }
            .setCancelable(true)
            .show()
    }

    private fun showStatus(view: View, item: TransferWrapper) {
        when (item.statusIcon) {
            C.ico.warning -> item.exceptionCause?.also { showWarningReasonAndSolution(it, item) }
            C.ico.done1 -> activity.showSnackBar(
                msg = "Download is in progress",
                duration = Snackbar.LENGTH_SHORT
            )
            C.ico.done2 -> activity.showSnackBar(
                msg = "Download completed",
                duration = Snackbar.LENGTH_SHORT
            )
            C.ico.stop -> activity.showSnackBar(
                msg = "Download is paused or stopped",
                duration = Snackbar.LENGTH_SHORT
            )
            C.ico.pending -> activity.showSnackBar(
                msg = "Waiting for another download to finish",
                duration = Snackbar.LENGTH_SHORT
            )
            C.ico.blank -> activity.showSnackBar(
                msg = "Fetching details of the download...",
                duration = Snackbar.LENGTH_SHORT
            )
        }
        log(
            "DownloadsListAdapter", "setOnClickListeners:",
            "status button clicked icon=${item.statusIcon}"
        )
    }

    @SuppressLint("InflateParams")
    private fun edit(item: TransferWrapper) {
        fun refreshLink() {
            if (Pref.isDownloadItemEditFirst) {
                activity.dialog = AlertDialog.Builder(activity)
                    .setTitle("Refresh download")
                    .setMessage(
                        """If your download link got expired, 
                        |you can go to browser and click on the download link again to get a fresh link.
                        |HTTP-Downloader can then resume the download from where it stopped<br/><br/>
                        |Click on <b>Refresh</b> to go to browser""".trimMargin().asHtml()
                    )
                    .setPositiveButton("Refresh") { d, _ ->
                        Pref.isDownloadItemEditFirst = false
                        d.dismiss()
                        refreshLink()
                    }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .show()
            } else {
                activity.showLongToast("Click on the download link to resume download")
                log("DownloadsListAdapter", "refreshLink")
                item.saveState(activity, true)
                Browser.start(
                    context = activity,
                    from = Browser.Companion.FromOptions.MAIN,
                    url = item.lastUrl,
                    request = C.misc.refresh + item.id
                )
            }
        }
        activity.snackBar?.dismiss()
        val view = activity.layoutInflater.inflate(R.layout.dialog_download_edit, null)
        view.findViewById<View>(R.id.refreshLink).setOnClickListener {
            activity.dialog?.dismiss()
            refreshLink()
        }
        view.findViewById<TextView>(R.id.downloadUrl).text = item.url
        view.findViewById<View>(R.id.copyUrl).setOnClickListener {
            activity.dialog?.dismiss()
            if (activity.copyToClipBoard(item.url))
                activity.showSnackBar("Copied URL to clipboard")
            else activity.showSnackBar("Failed to copy URL")
        }
        activity.dialog = AlertDialog.Builder(activity).setView(view).show()
    }

    fun showWarningReasonAndSolution(msg: String, item: TransferWrapper) {
        when {
            msg.contains("File could not be accessed") || msg.contains("Can't make file") -> {
                val message = "Can't access the <b>${item.fileName}</b>. " +
                        "Choose the its folder again to resume download"
                activity.showSnackBar(message, "Choose") {
                    try {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        activity.inaccessibleId = item.id
                        activity.startActivityForResult(intent, MainActivity.FILE_NOT_ACCESSIBLE)
                    } catch (e: Exception) {
                        activity.showSnackBar(
                            "Can't launch file picker",
                            duration = Snackbar.LENGTH_SHORT
                        )
                    }
                }
            }
            else -> activity.showSnackBar(msg)
        }
    }

    private val String.toResId: Int
        get() = MainActivity.decodeImgRes(this)

    private fun setViewProperties(view: View, item: TransferWrapper) {
        view.layoutParams = view.layoutParams.apply {
            height = if (item.isCollapsed) heightCollapsed else heightExpanded
            width = FrameLayout.LayoutParams.MATCH_PARENT
        }

        view.findViewById<ImageView>(R.id.download_icon).setImageResource(item.icon)

        item.statusIcon.toResId.also {
            with(view.findViewById<ImageView>(R.id.status)) {
                setImageResource(it)
                if (it == R.drawable.blank) hide()
                else unHide()
            }
        }
        if (item.shouldAnimateBtns) {
            val pauseBtn = view.findViewById<ImageView>(R.id.pauseIcon)
            val pauseIconId = item.pauseBtnIcon.toResId
            val stopBtn = view.findViewById<ImageView>(R.id.stopIcon)
            val stopIconId = item.stopBtnIcon.toResId

            if (pauseBtn.tag != pauseIconId)
                animFactory.revealBtn(pauseBtn, pauseIconId)
            if (stopBtn.tag != stopIconId)
                animFactory.revealBtn(stopBtn, stopIconId) { item.shouldAnimateBtns = false }
        } else {
            view.findViewById<ImageView>(R.id.pauseIcon).apply {
                val pauseIconId = item.pauseBtnIcon.toResId

                tag = pauseIconId; setImageResource(pauseIconId)
                if (pauseIconId == R.drawable.blank) hide()
                else unHide()
            }
            view.findViewById<ImageView>(R.id.stopIcon).apply {
                val stopIconId = item.stopBtnIcon.toResId

                tag = stopIconId; setImageResource(stopIconId)
                if (stopIconId == R.drawable.blank) hide()
                else unHide()
            }
        }

        view.findViewById<TextView>(R.id.title).text = item.title.shorten(30)
        view.findViewById<TextView>(R.id.offset).text = item.offset
        view.findViewById<TextView>(R.id.limit).text = item.limit
        view.findViewById<TextView>(R.id.percent).text = item.percent
        view.findViewById<TextView>(R.id.reach).text = item.reach
        view.findViewById<TextView>(R.id.speed).text = item.speed
        view.findViewById<TextView>(R.id.downloaded_n_outOf_m).text = item.downloaded
        view.findViewById<ProgressBar>(R.id.this_progress).progress = item.progress
        view.findViewById<ProgressBar>(R.id.progress_1).progress = item.progress

        if (item.isStatusProgressVisible) {
            view.findViewById<ProgressBar>(R.id.status_progress).unHide()
            view.findViewById<ImageView>(R.id.status).hide()
        } else {
            view.findViewById<ProgressBar>(R.id.status_progress).hide()
            view.findViewById<ImageView>(R.id.status).unHide()
        }

        (view.findViewById<ProgressBar>(R.id.progress_0).layoutParams as
                LinearLayout.LayoutParams).weight = item.weight0.toFloat()
        (view.findViewById<ProgressBar>(R.id.progress_1).layoutParams as
                LinearLayout.LayoutParams).weight = item.weight1.toFloat()
        (view.findViewById<ProgressBar>(R.id.progress_2).layoutParams as
                LinearLayout.LayoutParams).weight = item.weight2.toFloat()
        view.requestLayout()
    }

    private fun share(item: TransferWrapper?) {
        val json = item?.getEssentialJSON()
        if (json == null) {
            activity.showSnackBar("Some error occurred", duration = Snackbar.LENGTH_SHORT)
            return
        }
        fun showSharingOptions() {
            val sharingIntent = Intent(Intent.ACTION_SEND)
            sharingIntent.type = "text/plain"
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, "HTTP-Downloader Download data")
            sharingIntent.putExtra(Intent.EXTRA_TEXT, json.str)
            activity.startActivity(Intent.createChooser(sharingIntent, "Share via"))
        }

        fun showShareQR() {
            val view = activity.layoutInflater.inflate(R.layout.qr_code, null)
            val imageView = view.findViewById<ImageView>(R.id.qrCode)

            val metrics = DisplayMetrics()
            activity.windowManager.defaultDisplay.getMetrics(metrics)
            val size = (min(metrics.heightPixels, metrics.widthPixels) * 0.8).toInt()

            imageView.layoutParams = imageView.layoutParams.apply { width = size; height = size }

            activity.dialog = AlertDialog.Builder(activity).setView(view).show()
            CoroutineScope(Dispatchers.Main).launch {
                val qrString = json.str
                generateQRCode(qrString, size)?.also {
                    view.findViewById<ProgressBar>(R.id.progress).hide()
                    imageView.setImageBitmap(it)
                } ?: activity.showSnackBar("Some error occurred. Please try again")
            }
        }

        activity.dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.share_title)
            .setMessage(R.string.share_warning)
            .setPositiveButton("Share") { d, _ ->
                log("DownloadsListAdapter", "share: +ve")
                showSharingOptions()
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ ->
                log("DownloadsListAdapter", "share: -ve")
                d.dismiss()
            }
            .setNeutralButton("QR code") { d, _ ->
                log("DownloadsListAdapter", "share: qr")
                showShareQR()
                d.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun delete(view: View, item: TransferWrapper?) {
        if (item == null) return
        activity.snackBar?.dismiss()
        fun delete() {
            log("DownloadsListAdapter", "delete: item.id=${item.id}")
            if (TransferService.isRunning) {
                val intent = Intent(C.filter.REQUEST)
                intent.putExtra(C.misc.request, C.req.delete)
                intent.putExtra(C.dt.id, item.id)
                activity.doneListeners[C.req.delete] = { returnIntent ->
                    if (returnIntent.getStringExtra(C.misc.actionDone) == C.req.delete) {
                        view.startAnimation(animFactory.listRemoveAnim(view, heightExpanded) {
                            this.remove(item)
                            if (this.count == 0) activity.blankInfo.unHide()
                        })
                        File(configsFolder(activity), "${item.id}.json").delete()
                        activity.doneListeners.remove(C.req.delete)
                    }
                }
                activity.transferServiceConnection.request(intent)
            } else {
                view.startAnimation(animFactory.listRemoveAnim(view, heightExpanded) {
                    this.remove(item)
                    if (this.count == 0) activity.blankInfo.unHide()
                })
                File(configsFolder(activity), "${item.id}.json").delete()
            }
        }

        activity.dialog = AlertDialog.Builder(activity)
            .setTitle("Remove ${item.fileName}?")
            .setMessage(
                """Are you sure to remove <b>${item.fileName}</b> from list?<br/>
                |<b>Note:</b> This won't delete the file
            """.trimMargin().asHtml()
            )
            .setPositiveButton("Remove") { d, _ ->
                d.dismiss(); delete()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun stop(view: View, item: TransferWrapper?) {
        fun setEnabled(holder: View, enabled: Boolean) {
            holder.findViewById<ImageView>(R.id.stopIcon).apply {
                if (enabled) unHide() else hide()
            }
            holder.findViewById<ProgressBar>(R.id.progressStop).apply {
                if (enabled) hide() else unHide()
            }
            holder.isEnabled = enabled
        }

        if (item == null) return
        activity.snackBar?.dismiss()

        setEnabled(view, false)
        if (item.isStopped || item.hasSucceeded || item.hasFailed) {
            val from = if (item.offset.startsWith("0")) "beginning" else "specified offset"
            activity.dialog = AlertDialog.Builder(activity)
                .setTitle("Confirm restart")
                .setMessage("Are you sure to restart this download from $from?")
                .setCancelable(true)
                .setPositiveButton("Restart") { d, _ ->
                    log("restart", "Sending restart intent")
                    val intent = Intent(C.filter.REQUEST)
                    intent.putExtra(C.misc.request, C.req.restart)
                    intent.putExtra(C.dt.id, item.id)
                    activity.transferServiceConnection.request(intent)
                    item.hasSucceeded = false; item.hasFailed = false
                    activity.doneListeners[C.req.restart] = { returnIntent ->
                        if (returnIntent.getStringExtra(C.misc.actionDone) == C.req.restart) {
                            setEnabled(view, true)
                            activity.doneListeners.remove(C.req.restart)
                            cancelScheduler(item)
                        }
                    }
                    d.dismiss()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .setOnDismissListener { setEnabled(view, true) }
                .setOnCancelListener { setEnabled(view, true) }
                .show()
        } else {
            log("stop", "Sending stop intent")
            val intent = Intent(C.filter.REQUEST)
            intent.putExtra(C.misc.request, C.req.stop)
            intent.putExtra(C.dt.id, item.id)
            activity.transferServiceConnection.request(intent)
            activity.doneListeners[C.req.stop] = { returnIntent ->
                if (returnIntent.getStringExtra(C.misc.actionDone) == C.req.stop) {
                    setEnabled(view, true)
                    activity.doneListeners.remove(C.req.stop)
                }
            }
        }
    }

    private fun pause(view: View, item: TransferWrapper?) {
        if (item == null) return
        activity.snackBar?.dismiss()
        view.isEnabled = false
        if (item.hasSucceeded) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(
                    getOutputObject(activity, item).getUri(),
                    toMimeType(item.fileName)
                )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.showSnackBar("Can't open this file")
            }
            view.isEnabled = true
            return
        }
        val progress = view.findViewById<ProgressBar>(R.id.progressPause)
        val pauseButton = view.findViewById<ImageView>(R.id.pauseIcon)
        if (pauseButton.visibility != View.VISIBLE) {
            log("DownloadListItem: pause", "can't pause when pause icon is not visible")
            if (progress.visibility != View.VISIBLE)
                activity.showSnackBar("The server doesn't support Pause & resume")
            return
        }
        pauseButton.hide()
        progress.unHide()

        val intent = Intent(C.filter.REQUEST)
        when {
            item.isStopped -> intent.putExtra(C.misc.request, C.req.resumeStop)
            item.isPaused -> intent.putExtra(C.misc.request, C.req.resumePause)
            else -> intent.putExtra(C.misc.request, C.req.pause)
        }
        intent.putExtra(C.dt.id, item.id)
        log("DownloadsListAdapter", "pause: sending " + intent.getStringExtra(C.misc.request))
        activity.transferServiceConnection.request(intent)
        activity.doneListeners[C.req.pause] = { returnIntent ->
            val acceptableActions = arrayOf(C.req.resumePause, C.req.pause, C.req.resumeStop)
            val response = returnIntent.getStringExtra(C.misc.actionDone)
            if (response in acceptableActions) {
                log(
                    "DownloadsListAdapter", "pause: DoneListener triggered for ",
                    returnIntent.getStringExtra(C.misc.actionDone)
                )
                pauseButton.unHide()
                progress.hide()
                view.isEnabled = true
                activity.doneListeners.remove(C.req.pause)
                if (response == C.req.resumePause || response == C.req.resumeStop)
                    cancelScheduler(item)
            }
        }
    }

    operator fun iterator(): Iterator<TransferWrapper?> {
        return object : Iterator<TransferWrapper?> {
            var current = 0
            override fun hasNext(): Boolean {
                return current < count
            }

            override fun next(): TransferWrapper? {
                return getItem(current++)
            }
        }
    }

    private val Int.dp: Int
        get() {
            val metrics = activity.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }

    private fun toggle(wrapper: View, item: TransferWrapper?) {
        val from = wrapper.layoutParams.height
        val to = if (from == heightCollapsed) heightExpanded else heightCollapsed
        wrapper.startAnimation(animFactory.toggleAnim(wrapper, from, to))
        if (item != null) {
            item.isCollapsed = (to == heightCollapsed)
            syncState(item, mutableSetOf(C.dt.isCollapsed))
        }
    }

    private fun getTopParent(view: View, resIdToCheck: Int): View? {
        var v: View? = view.parent as View
        while (v != null && v.findViewById<View>(resIdToCheck) == null)
            v = v.parent as View
        return v
    }
}