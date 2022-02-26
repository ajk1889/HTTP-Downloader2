package resonance.http.httpdownloader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.ConnectionChecker
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.TransferWrapper
import java.io.File
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class TransferService : Service() {
    companion object {
        @Volatile
        var isRunning = false
        val moreThan: String get() = "bW9yZSB0aGFuIA=="
    }

    private lateinit var broadcastManager: LocalBroadcastManager
    lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationAdapter: NotificationAdapter
    private var tasks = mutableListOf<TransferWrapper>()

    private var timeOfFirstEmpty = Long.MAX_VALUE
    var lastBroadcastTime = 0L

    var isDownloadInitComplete: Boolean = false

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        processIntent(intent)
        if (!isRunning) {
            isRunning = true
            broadcastManager = LocalBroadcastManager.getInstance(this)
            log("TransferService", "onStartCommand:")
            notificationManager = NotificationManagerCompat.from(this)
            notificationAdapter = NotificationAdapter(this)

            //All received intents are queued until this fun is completed. They are then passed to onReceive after completion
            initializeDownloads()
            initializeProgressBroadCaster()

            startForeground(
                NotificationAdapter.ONGOING_NOTIFICATION_ID,
                notificationAdapter.showNotification()
            )
        }
        return START_NOT_STICKY
    }

    private val receivedQueue = LinkedBlockingQueue<Intent>()
    private fun processIntent(intent: Intent?) {
        if (intent == null) return
        if (!intent.getBooleanExtra(C.misc.fromQueue, false)) {
            if (intent.isDuplicate()) return
            ApplicationClass.lastReceivedIntentId = intent.id
        }

        //takes intents to queue until downloads init is complete; Intents in Q will be processed after completing init
        if (!this.isDownloadInitComplete) {
            log(
                "processIntent",
                "Downloads not initialized",
                "queueing intent (id=${intent.id})"
            )
            intent.putExtra(C.misc.fromQueue, true) //to avoid double checking of intentId
            this.receivedQueue.add(intent); return
        }
        if (intent.action == C.filter.REQUEST)
            processServiceIntent(intent)
        else if (intent.action == C.filter.START_DOWNLOAD)
            processStartDownloadIntent(intent)
    }

    private fun processServiceIntent(intent: Intent) {
        this.lastBroadcastTime = now()
        if (intent.getBooleanExtra(C.misc.refresh, false)) {
            log(
                "processIntent",
                "item need to be refreshed",
                "id=" + intent.getLongExtra(C.dt.id, -1)
            )
            val id = intent.getLongExtra(C.dt.id, -1)
            if (id != -1L) {
                val task = this.getTaskById(id)
                task?.updateCoreData(this)
                task?.transfer = null
            }
        }
        when (intent.getStringExtra(C.misc.request)) {
            C.req.start_new -> this.startNewTransfer(intent.getStringExtra(C.misc.transfer)!!)
            C.req.restart -> this.restart(intent.getLongExtra(C.dt.id, -1))
            C.req.stop -> this.stop(intent.getLongExtra(C.dt.id, -1))
            C.req.markStreamServer -> this.markStreamServer(
                intent.getBooleanExtra(C.dt.isStreamServer, false),
                intent.getLongExtra(C.dt.id, -1)
            )
            C.req.limitSpeed -> this.limitSpeed(
                intent.getLongExtra(C.dt.id, -1),
                intent.getIntExtra(C.dt.maxSpeed, Int.MAX_VALUE)
            )
            C.req.pause -> this.pause(intent.getLongExtra(C.dt.id, -1))
            C.req.resumeStop -> this.resumeStop(intent.getLongExtra(C.dt.id, -1))
            C.req.resumePause -> this.resumePause(intent.getLongExtra(C.dt.id, -1))
            C.req.reloadTask -> this.reloadTask(intent.getLongExtra(C.dt.id, -1))
            C.req.pauseAll -> this.pauseAll()
            C.req.sendAll -> this.sendAll()
            C.req.delete -> this.deleteTransfer(intent.getLongExtra(C.dt.id, -1))
            C.req.syncData -> this.syncData(intent)
            C.req.startScheduled -> this.startScheduled()
            C.req.retryFailed -> this.retryFailed()
        }
    }

    private fun processStartDownloadIntent(intent: Intent) {
        fun getBasicData(): Any = try {
            val json = JSONObject(intent.getStringExtra(C.misc.downloadDataJSON))
            "from = " + json.opt(C.misc.from) + " id = " + json.opt(C.dt.id)
        } catch (e: Exception) {
            e
        }
        this.lastBroadcastTime = now()
        log("processStartDownloadIntent", "called", getBasicData())
        val data = JSONObject(intent.getStringExtra(C.misc.downloadDataJSON))
        silently {
            data.optString(C.dt.fileName, null).toNameNExtension().second?.also {
                log("file-extension", it)
            }
        }
        addDownload(data)
    }

    private fun addDownload(downloadData: JSONObject) {
        if (!downloadData.has(C.dt.id)) downloadData.put(C.dt.id, getUniqueId())
        if (!downloadData.has(C.dt.outputFolder))
            downloadData.put(C.dt.outputFolder, C.defaultOutputFolder)
        this.startNewTransfer(downloadData.str)
    }

    override fun onDestroy() {
        log("TransferService", "onDestroy:")
        for (task in tasks.toTypedArray()) task.transfer?.stop()
        isRunning = false
        saveAllTransfersStates()
        stopForeground(true)
        super.onDestroy()
    }

    private fun initializeDownloads() {
        isDownloadInitComplete = false
        thread {
            // compatibility for old users
            // todo remove after a long period of time
            val oldFolder = File(getExternalFilesDir(null), "configs")
            if (oldFolder.exists())
                oldFolder.copyRecursively(File(filesDir, "configs"))
            oldFolder.deleteRecursively()

            tasks = TransferWrapper.loadTransfers(this) {
                it.isServiceMode = true
                it.outputObject = getOutputObject(this, it)
                if (it.onEndAction != null) {
                    //defining onEndInitializer at time of loading; will be invoked during transfer initialization
                    it.onEndInitializer = { transfer, _ ->
                        transfer.startNextDownload = { id ->
                            val next = getTaskById(id)
                            if (next != null && next.transfer == null) {
                                startNewTransfer(next.getAbsoluteJson().str)
                                log("startNextDownload", "Started download (id = $id)")
                            }
                        }
                    }
                }
            }
            isDownloadInitComplete = true
            log("TransferService", "initializeDownloads: loaded ${tasks.size} items")

            log(
                "TransferService",
                "initializeDownloads: Service initialization success: Sending ping response"
            )
            sendPingResponse()
            log(
                "TransferService",
                "initializeDownloads: calling onReceive for ${receivedQueue.size} queued intents"
            )
            while (receivedQueue.isNotEmpty())
                processIntent(receivedQueue.remove())
        }
    }

    private fun initializeProgressBroadCaster() {
        fun sendProgress(tasks: MutableList<TransferWrapper>) {
            if (tasks.size == 0 || !MainActivity.areBroadcastReceiversAlive) {
                //need not send broadcast if broadcast receivers aren't alive in MainActivity
                return
            }
            val intent = Intent(C.filter.PROGRESS_UPDATE)
            intent.putExtra(C.dt.id, LongArray(tasks.size) { tasks[it].id })
            intent.putExtra(C.dt.progress, IntArray(tasks.size) { tasks[it].progress })
            intent.putExtra(C.dt.downloaded, Array(tasks.size) { tasks[it].downloaded })
            intent.putExtra(C.dt.speed, Array(tasks.size) { tasks[it].speed })
            intent.putExtra(C.dt.reach, Array(tasks.size) { tasks[it].reach })
            intent.putExtra(C.dt.percent, Array(tasks.size) { tasks[it].percent })
            intent.id = ++ApplicationClass.lastSentIntentId
            broadcastManager.sendBroadcast(intent)

            // sending pending status updates when broadcast receivers become alive
            if (pendingStatusUpdatesExist && MainActivity.areBroadcastReceiversAlive) {
                log("TransferService", "sendProgress: Sending all pending status updates")
                for (task in this.tasks.toTypedArray())
                    updateStatus(task, "pending status update")
                pendingStatusUpdatesExist = false
            }
        }

        var speedUpdateCounter = 0L
        //speed is updated only once in 3 progress updates
        val speedUpdateTimePeriod = 3L
        var saveStateCounter = 0L
        //current state of transfer is saved to disk once in 5 progress updates
        val saveStateTimePeriod = 5L


        Transfer.setInterval(
            function = {
                notificationAdapter.showNotification()
                val runningTasks = mutableListOf<TransferWrapper>()
                val now = now()
                speedUpdateCounter += 1
                saveStateCounter += 1
                for (task in tasks.toTypedArray()) {
                    with(task.transfer ?: continue) {
                        if (task.isOngoing) {
                            if (this !is DownloadObject || !isFetchRunning)
                                task.updateTaskProgress(speedUpdateCounter % speedUpdateTimePeriod == 0L)
                            notificationAdapter.addOrUpdate(task)
                            runningTasks.add(task)
                            if (saveStateCounter % saveStateTimePeriod == 0L)
                                task.saveState(this@TransferService)
                        }
                    }
                }
                sendProgress(runningTasks)

                //stop service when no task is ongoing
                if (runningTasks.isEmpty()) {
                    if (timeOfFirstEmpty > now) timeOfFirstEmpty = now
                    else if (now - timeOfFirstEmpty > 500 && now - lastBroadcastTime > 500) {
                        log(
                            "TransferService",
                            "initializeProgressBroadCaster: setInterval: No transfer running for past 500ms. Ending service"
                        )
                        stopSelf(); return@setInterval
                    }
                } else timeOfFirstEmpty = Long.MAX_VALUE
            },
            runWhile = { isRunning }
        )
    }

    private fun sendPingResponse() {
        val intent = Intent(C.filter.PING)
        intent.id = ++ApplicationClass.lastSentIntentId
        broadcastManager.sendBroadcast(intent)
        log("TransferService", "sendPingResponse: sent")
    }


    private fun restart(id: Long) {
        log("TransferService", "restart: id=$id")
        val task = getTaskById(id) ?: return
        log("TransferService", "restart: such a task exists, restarting")
        if (task.type == DownloadObject.TYPE) {
            if (task.outputObject.reset()) {
                val download = task.initializeTransfer() as DownloadObject
                task.written = 0
                download.toOutputObject = { getOutputObject(this, task) }
                startDownload(task, download)

                task.isStopped = false; task.isPaused = false; task.hasFailed = false
                task.stopBtnIcon = C.ico.stop
                task.pauseBtnIcon = C.ico.pause
                task.isStatusProgressVisible = true
                val extraParams: MutableMap<String, Any?> =
                    mutableMapOf(C.dt.shouldAnimateBtns to true)
                updateStatus(task, "restart notification", extraParams)
                log("TransferService", "restart: successful; intent sent")
            } else {
                log("TransferService", "restart: failed to reset outputObject")
                showSnackBar(
                    "Failed to delete previously downloaded file<br>" +
                            "Please click on share icon and use that data to restart download"
                )
            }
        }
    }

    private fun pause(id: Long, showWarning: Boolean = true) {
        log("TransferService", "pause: id=$id")
        val item = getTaskById(id) ?: return
        log("TransferService", "pause: item exist; item.type=${item.type}")
        if (item.type == DownloadObject.TYPE) {
            with(item.transfer as DownloadObject?) {
                if (this == null) return@with
                log("TransferService", "pause: response=" + response.nuLliTy())
                response?.also {
                    if (it.isContentLengthType) {
                        item.isPaused = false
                        item.pauseBtnIcon = C.ico.blank
                        updateStatus(item, "Pause unavailable")
                        if (showWarning) showSnackBar(
                            "<font color='#FF7D66'>This download doesn't support pause & resume.</font><br/>" +
                                    "If you stop the download, it will <b>restart from beginning</b> next time"
                        )
                        sendDoneStatus(C.req.pause)
                        return
                    } else if (item.isPaused) {
                        item.speed = "---"
                        item.isStopped = true
                        item.stopBtnIcon = C.ico.restart
                        item.pauseBtnIcon = C.ico.resume
                        item.statusIcon = C.ico.stop
                        item.isStatusProgressVisible = false
                        updateStatus(item, "already paused")
                        sendDoneStatus(C.req.pause)
                        return
                    }
                }
            }
            item.isPaused = true
            item.transfer?.isPaused = true
            stop(id)
            sendDoneStatus(C.req.pause)
            log("TransferService", "pause: called stop $id; sending Done")
        }
    }

    private fun resumePause(id: Long) {
        log("TransferService", "resumePause: id=$id")
        val item = getTaskById(id) ?: return
        log(
            "TransferService", "resumePause: item exists;",
            "item.type=${item.type} id=${item.id}"
        )
        if (item.type == DownloadObject.TYPE) {
            if (!(item.isPaused || item.isStopped || item.hasFailed)) {
                item.pauseBtnIcon = C.ico.pause
                item.stopBtnIcon = C.ico.stop
                item.isStatusProgressVisible = false
                updateStatus(item, "task $id not paused, stopped or failed; can't be resumed")
                sendDoneStatus(C.req.resumePause)
                return
            }
            val download = (item.transfer ?: item.initializeTransfer()) as DownloadObject
            val response = download.response
            if (response == null) {
                sendDoneStatus(C.req.resumePause)
                startDownload(item, download)
                log("TransferService", "resumePause: starting download")
                return
            }
            if (response.isContentLengthType) {
                showSnackBar("<b>Sorry</b>, This download cannot be resumed")
            } else {
                item.transfer?.isPaused = false
                resumeStop(id)
            }
            sendDoneStatus(C.req.resumePause)
        }
    }

    private fun resumeStop(id: Long) {
        log("TransferService", "resumeStop: id=$id")
        val task = getTaskById(id) ?: return
        log(
            "TransferService", "resumeStop: item exist;",
            "item.type=${task.type}", "absolute=${task.getAbsoluteJson()}"
        )
        if (!(task.isPaused || task.isStopped || task.hasFailed)) {
            task.pauseBtnIcon = C.ico.pause
            task.stopBtnIcon = C.ico.stop
            task.isStatusProgressVisible = false
            updateStatus(task, "task $id not stopped; can't be resumed")
            sendDoneStatus(C.req.resumeStop)
            return
        }
        val transfer = task.transfer ?: task.initializeTransfer()

        log("TransferService", "resumeStop: transfer & task ready")
        task.isStopped = false; task.isPaused = false; task.hasFailed = false
        task.stopBtnIcon = C.ico.stop
        task.pauseBtnIcon = C.ico.pause
        task.isStatusProgressVisible = true
        val extraParams: MutableMap<String, Any?> = mutableMapOf("shouldAnimateBtns" to true)
        updateStatus(task, "resumeStop notification", extraParams)
        log("TransferService", "resumeStop: successful")

        // if some notification saying this item was failed, remove it
        // otherwise user may get confused
        notificationAdapter.cancelFailedNotification(task)

        if (transfer is DownloadObject) startDownload(task, transfer)
        log("TransferService", "resumeStop: started download")
        sendDoneStatus(C.req.resumeStop)
    }

    private fun stop(id: Long) {
        log("TransferService", "stop: id=$id")
        val task = getTaskById(id) ?: return
        log("TransferService", "stop: task exist; type=${task.type}")
        if (task.transfer != null) {
            log("TransferService", "stop: stopping id=$id")
            task.transfer!!.stop()
        } else log(
            "TransferService",
            "stop: Transfer(id=$id) has not yet been initialized. Executing onStop directly"
        )

        task.transfer?.also {
            if (it is DownloadObject && it.response != null)
                task.updateTaskProgress(false)
        }
        task.speed = "---"
        task.isStopped = true
        task.stopBtnIcon = C.ico.restart
        task.statusIcon = C.ico.stop
        task.isStatusProgressVisible = false
        task.transfer.also {
            if (it != null && it is DownloadObject && it.response?.isContentLengthType == true) {
                log("TransferService", "stop: transfer is isContentLengthType; hiding pauseBtn")
                task.pauseBtnIcon = C.ico.blank
            } else task.pauseBtnIcon = C.ico.resume
        }
        log("TransferService", "stop: stopped successfully")
        val extraParams: MutableMap<String, Any?> = mutableMapOf("shouldAnimateBtns" to true)
        updateStatus(task, "Status after stopping transfer", extraParams)

        sendDoneStatus(C.req.stop)
    }

    private fun deleteTransfer(id: Long) {
        stop(id)
        tasks.remove(getTaskById(id))
        sendDoneStatus(C.req.delete)
    }

    private fun startNewTransfer(json: String) {
        val jsonObject = JSONObject(json)
        log("TransferService", "startNewTransfer: from=" + jsonObject.optString("from"))
        log("TransferService", "startNewTransfer: " + jsonObject.keys().list())
        val task = TransferWrapper(jsonObject, isServiceMode = true).also {
            it.isServiceMode = true
            if (it.onEndAction != null) {
                //defining onEndInitializer at time of loading; will be invoked during transfer initialization
                it.onEndInitializer = { transfer, _ ->
                    log("TransferService", "startNewTransfer: onEndInitializer invoked")
                    transfer.startNextDownload = { id ->
                        val next = getTaskById(id)
                        if (next != null && next.transfer == null) {
                            startNewTransfer(next.getAbsoluteJson().str)
                            log(
                                "TransferService",
                                "startNewTransfer: startNextDownload invoked next id=$id"
                            )
                        }
                    }
                }
            }
            it.saveState(this, true)
        }
        with(tasks.indexOf(task)) {
            if (this == -1) tasks.add(task)
            else tasks[this] = task
        }
        if (task.type == DownloadObject.TYPE) {
            if (task.previousTaskEnded()) {
                startDownload(task, task.initializeTransfer() as DownloadObject)
            } else {
                task.isPaused = true
                task.stopBtnIcon = C.ico.blank
                task.pauseBtnIcon = C.ico.resume
                task.statusIcon = C.ico.pending
                task.isStatusProgressVisible = false
                task.title = task.fileName.shorten(14) + " : waiting.."
                updateStatus(task, "task ${task.id} will start after ${task.startAfter}")
                log(
                    "TransferService",
                    "startNewTransfer: task ${task.id} will start after ${task.startAfter}"
                )
            }
        }
        sendDoneStatus(C.req.start_new)
    }

    private fun sendDoneStatus(actionDone: String) {
        val intent = Intent(C.filter.DONE)
        intent.putExtra(C.misc.actionDone, actionDone)
        intent.id = ++ApplicationClass.lastSentIntentId
        log("TransferService", "sendDoneStatus: $actionDone")
        broadcastManager.sendBroadcast(intent)
    }

    private var pendingStatusUpdatesExist = false
    private fun updateStatus(
        task: TransferWrapper,
        descr: String,
        extra: (MutableMap<String, Any?>) = HashMap()
    ) {
        if (!MainActivity.areBroadcastReceiversAlive) {
            // the broadcast will not be captured; Need not send it. The changes map will get cleared & may cause data loss
            // The whole updates can be sent later, when receivers are active, from progress update thread
            pendingStatusUpdatesExist = true
            log("TransferService", "updateStatus: pending for ${task.id}; descr=$descr")
            return
        }
        if (task.dontHaveChanges() && extra.isEmpty()) {
            log("TransferService", "updateStatus: No status update for ${task.id}; descr=$descr")
            return
        }
        task.saveState(this)
        with(task.lastUpdatesJson) {
            for (i in extra) this.put(i.key, i.value)

            val intent = Intent(C.filter.STATUS_CHANGE)
            intent.putExtra(C.misc.task, this.str)
            intent.putExtra(C.misc.description, descr)
            intent.id = ++ApplicationClass.lastSentIntentId
            notificationAdapter.addOrUpdate(task)
            broadcastManager.sendBroadcast(intent)
            log("TransferService", "updateStatus: of ${task.id}; message=$descr changes=$this")
        }
    }

    private fun showSnackBar(msg: String) {
        val intent = Intent(C.filter.SHOW_MSG)
        intent.putExtra(C.misc.message, msg)
        intent.id = ++ApplicationClass.lastSentIntentId
        broadcastManager.sendBroadcast(intent)
        log("TransferService", "showSnackBar: msg=$msg")
    }

    private val startTransferQueue = LinkedBlockingQueue<Transfer>()
    private val lock = ReentrantLock()

    private fun startDownload(task: TransferWrapper, download: DownloadObject) {
        if (download.isFetchRunning) {
            log("TransferService", "startDownload: Cancelling 2nd request")
            return
        }
        fun unHideStatusProgress() {
            task.isStatusProgressVisible = true
            task.pauseBtnIcon = C.ico.pause
            task.stopBtnIcon = C.ico.stop
            task.isStopped = false; task.isPaused = false; task.hasFailed = false
            task.statusIcon = C.ico.blank
            task.title = task.fileName
            log("TransferService", "unHideStatusProgress: ")
            updateStatus(task, "To make status_progress visible until fetching details")
        }

        fun updateWriteTotal() {
            //for finding "writeTotal", may be overwritten later
            task.outputObject = getOutputObject(this, task)
            val total = if (task.emptyFirstMode) task.written
            else task.outputObject.length()
            download.writeTotal = total
            log("TransferService", "updateWriteTotal: writeTotal=$total=${formatSize(total)}")
        }

        fun onDownloadSuccess(transfer: Transfer) {
            task.statusIcon = C.ico.done2
            task.isStatusProgressVisible = false
            task.hasSucceeded = true
            task.pauseBtnIcon = C.ico.open
            task.stopBtnIcon = C.ico.restart
            task.isCollapsed = true
            task.updateTaskProgress()
            Pref.retryList.remove(task)
            log("TransferService", "onDownloadSuccess: " + transfer.id)
            val scanner = MediaScanner(this)
            when {
                task.outputFolder.isFile() ->
                    scanner.scan(task.fileName, task.outputFolder.toFolder())
                task.outputFolder.isUri() ->
                    scanner.scan(task.fileName, task.outputFolder.toUri())
                task.outputFolder.isUriFile() ->
                    scanner.scan(task.outputFolder.toUriFile(this))
            }
            log("TransferService", "onDownloadSuccess: mediaScanning complete")
            updateStatus(task, "Download completed notification")
        }

        fun onDownloadFailed(reason: String) {
            task.statusIcon = C.ico.warning
            task.isStatusProgressVisible = false
            task.exceptionCause = reason
            task.hasFailed = true
            task.isStopped = true
            task.pauseBtnIcon = C.ico.resume
            task.stopBtnIcon = C.ico.restart
            task.isCollapsed = true
            task.updateTaskProgress()
            Pref.retryList.add(task)
            when {
                reason.contains("maximum parallel downloads") ->
                    log("TS", "onDownloadFailed: reason=maxParallel limit")
                reason.contains("Link might be expired") ->
                    log("TS", "onDownloadFailed: reason=link expired")
                else -> log("TS", "onDownloadFailed: reason=$reason")
            }
            updateStatus(task, "Download failed notification")
        }

        fun onFetchSuccess(obj: DownloadObject, response: DownloadObject.ResponseData?) {
            Pref.retryList.remove(obj.id)
            if (checkTaskIsPaused(task)) {
                obj.stopTime = now()
                return
            }
            if (response == null) {
                task.isStatusProgressVisible = false
                updateStatus(task, "Download already completed")
                showSnackBar("Download already completed")
                obj.stopTime = now()
                return
            }
            val (isResponseAcceptable, reason) = validateResponseCode(response)
            if (!isResponseAcceptable) {
                log("TransferService", "onFetchSuccess: response not acceptable: $reason")
                showSnackBar(reason)
                task.isStatusProgressVisible = false
                updateStatus(task, "Response not acceptable: change status_progress invisible")
                obj.stopTime = now()
                return
            }
            task.isStatusProgressVisible = false
            task.statusIcon = C.ico.done1
            task.computeWeights(download)
            if (response.isContentLengthType) {
                log(
                    "TransferService",
                    "onFetchSuccess: response is content length type; setting pauseBtn=blank"
                )
                task.pauseBtnIcon = C.ico.blank
                task.contentLengthType = true
            }
            updateStatus(task, "Modifying weights & params after fetching details")

            if (task.fileName == "") {
                log(
                    "TransferService",
                    "onFetchSuccess: filename was blank; setting new",
                    "appendConflicts=${Pref.appendConflictingFiles}"
                )
                var file = response.fileName
                if (!Pref.appendConflictingFiles) {
                    while (filePreExists(file, task.outputFolder, this))
                        file = renameDuplicateFile(file)
                    task.fileName = file
                    task.saveState(this)
                    task.outputObject = getOutputObject(this, task)
                } else {
                    task.fileName = file
                    task.saveState(this)
                    task.outputObject = getOutputObject(this, task)
                    download.writeTotal = task.outputObject.length()
                    if (download.writeTotal != 0L) {
                        download.startFetchingDetails()
                        // download.stopTime = now() not required; it will be recursively applied
                        return
                    }
                }
            } else task.outputObject = getOutputObject(this, task)
            download.toOutputObject = { getOutputObject(this, task) }

            if (isRunning) {
                lock.withLock {
                    if (startTransferQueue.isEmpty()) {
                        startTransferQueue.add(download)
                        log(
                            "TransferService",
                            "onFetchSuccess: Q empty; adding & running ${download.id}"
                        )
                        checkAndDownload(getTaskById(download.id)!!, download)
                    } else {
                        log(
                            "TransferService",
                            "onFetchSuccess: Q not empty; adding ${download.id} to Q"
                        )
                        startTransferQueue.add(download)
                    }
                }
            }
        }

        fun onFailed(obj: DownloadObject, exception: Exception) {
            task.exceptionCause = getExceptionCause(exception, "fetch_failed")
            task.isStatusProgressVisible = false
            task.statusIcon = C.ico.warning
            task.pauseBtnIcon = C.ico.resume
            task.stopBtnIcon = C.ico.restart
            task.speed = "---"
            task.isStopped = true
            task.isCollapsed = true
            obj.stopTime = now()
            when (exception) {
                is FileNotFoundException ->
                    log("TransferService", "onFailed: fileNotFound")
                is SocketTimeoutException ->
                    log("TS", "onFailed: timeOut")
                is RuntimeException ->
                    log("TransferService", "onFailed: ", exception.message)
                else -> log("TS", "onFailed: ", exception)
            }
            val extraParams: MutableMap<String, Any?> = mutableMapOf("shouldAnimateBtns" to true)
            updateStatus(task, "Fetch failed notification", extraParams)
        }


        if (!task.isStatusProgressVisible) unHideStatusProgress()
        try {
            if (task.fileName != "") updateWriteTotal()
            download.onFetchSuccess = ::onFetchSuccess
            download.onFetchFailed = ::onFailed
            download.onSuccess = ::onDownloadSuccess
            download.onFailed = ::onDownloadFailed
            download.startFetchingDetails()
        } catch (e: Exception) {
            onFailed(download, e)
        }
    }

    private fun checkTaskIsPaused(task: TransferWrapper) = if (task.isPaused) {
        task.isStatusProgressVisible = false
        task.pauseBtnIcon = C.ico.resume
        task.stopBtnIcon = C.ico.restart
        task.statusIcon = C.ico.stop
        updateStatus(task, "paused transfer cannot be started")
        true
    } else false

    private fun checkAndDownload(task: TransferWrapper, download: Transfer) {
        val ongoing = ongoingCount
        val maxParallel = Pref.maxParallelDownloads
        if (ongoing >= maxParallel) {
            log(
                "TransferService",
                "checkAndDownload: Ongoing($ongoing)>$maxParallel for ${download.id}"
            )
            val msg =
                "<b>maximum parallel downloads</b> is set to <b>$maxParallel</b> in settings.<br/>" +
                        "Go to <b><i>settings</i></b> and increase it OR<br/>" +
                        "Pause/Stop any other ongoing download<br/>" +
                        "And try again"
            while (!startTransferQueue.isEmpty())
                startTransferQueue.remove().apply {
                    stopTime = now()
                    onFailed?.invoke(msg)
                }
        } else {
            log(
                "TransferService",
                "checkAndDownload: ongoing($ongoing)<$maxParallel for ${download.id}"
            )
            download.onStartIO = {
                lock.withLock {
                    log(
                        "TransferService",
                        "checkAndDownload: startIO called for ${download.id}"
                    )
                    // avoids crash from explicit calling
                    if (startTransferQueue.isEmpty()) return@withLock
                    startTransferQueue.remove()
                    if (!startTransferQueue.isEmpty()) {
                        val next = startTransferQueue.element()
                        log(
                            "TransferService",
                            "checkAndDownload: checking ${download.id}"
                        )
                        checkAndDownload(getTaskById(next.id)!!, next)
                    }
                }
            }
            if (isRunning) {
                lock.withLock {
                    if (checkTaskIsPaused(task)) return@withLock
                    task.isStopped = false; task.isPaused = false
                    task.hasSucceeded = false; task.hasFailed = false
                    updateStatus(task, "Beginning IO")
                    log("TransferService", "checkAndDownload: starting ${download.id}")
                    if (isRunning) download.startTransfer()
                    else log(
                        "TransferService",
                        "checkAndDownload: Service not running 1. Cancelling ${download.id}"
                    )
                }
            } else log(
                "TransferService",
                "checkAndDownload: Service not running 2. Cancelling ${download.id}"
            )
        }
    }

    private fun saveAllTransfersStates() {
        val taskArr = tasks.toTypedArray()
        log("TransferService", "saveAllTransfersStates: count=" + taskArr.size)
        for (task in taskArr) {
            val obj = task.getAbsoluteJson()
            if (!task.hasSucceeded) {
                obj.put(C.dt.pauseBtnIcon, C.ico.resume)
                obj.put(C.dt.stopBtnIcon, C.ico.restart)
                obj.put(C.dt.speed, "---")
                obj.put(C.dt.isStopped, true)
                obj.put(C.dt.isPaused, false)
            }
            task.saveState(this)
        }
        log("TransferService", "saveAllTransfersStates: Done")
    }

    private fun validateResponseCode(response: DownloadObject.ResponseData) =
        if (response.code in 200..299) true to "Success"
        else false to "Wrong response code: ${response.code}"

    private fun getTaskById(id: Long): TransferWrapper? {
        var task: TransferWrapper? = null
        for (t in tasks.toTypedArray())
            if (t.id == id) {
                task = t; break
            }
        if (task == null) log("TransferService", "getTaskById: no tasks with id=$id")
        return task
    }

    private val TransferWrapper.hasEnded: Boolean
        get() = isStopped || hasFailed || hasSucceeded

    private fun TransferWrapper.previousTaskEnded(): Boolean {
        val after = startAfter ?: return true
        if (after.startsWith("ending ")) {
            val id = after.substring(7).toLong()
            return getTaskById(id)?.hasEnded == true
        }
        throw java.lang.RuntimeException("Unknown startAfter parameter for task $id")
    }

    private fun reloadTask(id: Long, count: Int = 0) {
        if (count > 2) return
        log("TransferService", "reloadTask: Called reload for $id")
        try {
            for (i in tasks.indices) {
                if (tasks[i].id == id) {
                    tasks.removeAt(i).makeNull()
                    tasks.add(i, TransferWrapper.loadTransfer(this, id))
                    log("TransferService", "reloadTask: reloaded $id")
                    break
                }
            }
        } catch (e: Exception) {
            // May come by list index out of range due to ConcurrentModification from another thread
            reloadTask(id, count + 1)
        }
    }

    private fun pauseAll() {
        log("TransferService", "pauseAll: called")
        for (task in tasks.toTypedArray())
            if (task.isOngoing) pause(task.id, false)
    }

    private fun sendAll() {
        log("TransferService", "sendAll: ")
        val all = Array(tasks.size) { tasks[it].getAbsoluteJson().str }
        val intent = Intent(C.filter.ALL_TRANSFERS)
        intent.putExtra(C.misc.allTransfers, all)
        intent.id = ++ApplicationClass.lastSentIntentId
        broadcastManager.sendBroadcast(intent)
    }

    private fun markStreamServer(isStreamServer: Boolean, id: Long) {
        val item = getTaskById(id) ?: return
        item.isStreamServer = isStreamServer
        item.transfer?.also {
            (it as DownloadObject).isStreamingServer = isStreamServer
        }
        updateStatus(item, "stream server status changed")
    }

    private fun limitSpeed(id: Long, maxSpeed: Int) {
        val item = getTaskById(id) ?: return
        item.maxSpeed = maxSpeed
        item.transfer?.localSpeedLimit = maxSpeed
        updateStatus(item, "max speed changed to ${formatSize(maxSpeed.toLong(), 2, " ")}/s")
    }

    /**
     * Syncs data of transfer object from mainActivity
     */
    private fun syncData(intent: Intent) {
        val item = getTaskById(intent.getLongExtra(C.dt.id, -1)) ?: return
        val data = JSONObject(intent.getStringExtra(C.misc.task))
        item.copyContentsFrom(data)
    }

    private fun startScheduled() {
        val now = now()
        for (task in tasks) {
            if (task.scheduledTime in (now - 10.seconds)..(now + 49.seconds)) {
                if (task.isOngoing) continue
                if (task.isStopped) resumeStop(task.id)
                else if (task.isPaused) resumePause(task.id)
            }
        }
    }

    private fun retryFailed() {
        var retriedCount = 0
        for (taskId in Pref.retryList) {
            if (getTaskById(taskId)?.isOngoing == true) continue
            resumeStop(taskId)
            retriedCount += 1
        }
        stopService(Intent(this, ConnectionChecker::class.java))
        if (retriedCount > 0) {
            if (retriedCount == 1)
                showSnackBar("Retried a download")
            else showSnackBar("Retried $retriedCount downloads")
        }
    }

    private val ongoingCount: Int
        get() {
            var count = 0
            for (task in tasks.toTypedArray()) {
                val transfer = (task.transfer ?: continue) as DownloadObject
                if (transfer.isTransferRunning) count += 1
            }
            return count
        }
}