package resonance.http.httpdownloader.helpers

import android.os.Environment
import resonance.http.httpdownloader.BuildConfig
import java.io.File

//class containing constant values
class C {
    object dt {
        const val maxSpeed = "maxSpeed"
        const val scheduledTime = "scheduledTime"
        const val contentLengthType = "contentLengthType"
        const val lastUrl = "lastUrl"
        const val title = "title"
        const val url = "url"
        const val headers = "headers"
        const val id = "id"
        const val fileName = "fileName"
        const val icon = "icon"
        const val type = "type"
        const val emptyFirstMode = "emptyFirstMode"
        const val outputFolder = "outputFolder"
        const val statusIcon = "statusIcon"
        const val pauseBtnIcon = "pauseBtnIcon"
        const val stopBtnIcon = "stopBtnIcon"
        const val shouldAnimateBtns = "shouldAnimateBtns"
        const val isCollapsed = "isCollapsed"
        const val isStreamServer = "isStreamServer"
        const val isStopped = "isStopped"
        const val isPaused = "isPaused"
        const val hasSucceeded = "hasSucceeded"
        const val hasFailed = "hasFailed"
        const val isStatusProgressVisible = "isStatusProgressVisible"
        const val written = "written"
        const val progress = "progress"
        const val percent = "percent"
        const val reach = "reach"
        const val speed = "speed"
        const val downloaded = "downloaded"
        const val weight0 = "weight0"
        const val weight1 = "weight1"
        const val weight2 = "weight2"
        const val offset = "offset"
        const val limit = "limit"
        const val onEndAction = "onEndAction"
        const val startAfter = "startAfter"
        const val exceptionCause = "exceptionCause"
        const val partsCount = "partsCount"
    }

    object ico {
        const val open = "open"
        const val blank = "blank"
        const val pause = "pause"
        const val stop = "stop"
        const val pending = "pending"
        const val warning = "warning"
        const val resume = "resume"
        const val restart = "restart"
        const val scheduled = "scheduled"
        const val done1 = "done1"
        const val done2 = "done2"
    }

    object type {
        const val file = "file"
        const val uri = "uri"
        const val fileUri = "fileUri"
    }

    object joiner {
        const val inputs = "inputs"
        const val output = "output"

        const val totalSize = "totalSize"
        const val totalReach = "totalReach"
        const val totalProgress = "totalProgress"
        const val speed = "speed"
        const val currentPart = "currentPart"
        const val currentPartSize = "currentPartSize"
        const val currentReach = "currentReach"
        const val partProgress = "partProgress"

        const val first = "first"
        const val deleteAfterJoin = "deleteAfterJoin"
    }

    object misc {
        const val pauseAll = "pauseAll"
        const val shared = "shared"
        const val downloadDataJSON = "downloadDataJSON"
        const val fileJoinerDataJSON = "fileJoinerDataJSON"
        const val task = "task"
        const val description = "description"
        const val intentId = "intentId"
        const val message = "message"
        const val from = "from"
        const val request = "request"
        const val browser = "browser"
        const val startDownload = "startDownload"
        const val saveToPreferences = "saveToPreferences"
        const val saveForAdvancedMode = "saveForAdvancedMode"
        const val transfer = "transfer"
        const val fromQueue = "fromQueue"
        const val fileNameSuffix = "fileNameSuffix"
        const val actionDone = "actionDone"
        const val refresh = "refresh: "
        const val allTransfers = "allTransfers"
        const val qrScanResult = "qrScanResult"
        const val childToScrollTo = "childToScrollTo"
    }

    object filter {
        const val JOINING_FAILED = "JOINING_FAILED"
        const val ALL_TRANSFERS = "ALL_TRANSFERS"
        const val REQUEST = "REQUESTS"
        const val SHOW_MSG = "SHOW_MSG"
        const val PROGRESS_UPDATE = "PROGRESS_UPDATE"
        const val STATUS_CHANGE = "STATUS_CHANGE"
        const val PING = "PING"
        const val START_DOWNLOAD = "START_DOWNLOAD"
        const val DONE = "DONE"
        const val JOINER_PROGRESS = "JOINER_PROGRESS"
        const val runScheduledTasks = "resonance.http.httpdownloader.RUN_SCHEDULED"
        const val globalStartDownload = "resonance.http.httpdownloader.START_DOWNLOAD"
    }

    object req {
        const val retryFailed = "retryFailed"
        const val cancelAutoRetry = "cancelAutoRetry"
        const val startScheduled = "startScheduled"
        const val limitSpeed = "limitSpeed"
        const val delete = "delete"
        const val syncData = "syncData"
        const val sendAll = "sendAll"
        const val pauseAll = "pauseAll"
        const val start_new = "start_new"
        const val restart = "restart"
        const val stop = "stop"
        const val markStreamServer = "markStreamServer"
        const val pause = "pause"
        const val resumeStop = "resumeStop"
        const val resumePause = "resumePause"
        const val reloadTask = "reloadTask"
    }

    companion object {
        val INTERNAL_DOWNLOAD_FOLDER =
            File(Environment.getExternalStorageDirectory(), "HTTP-Downloads").also {
                if (!it.exists()) it.mkdirs()
            }
        val defaultOutputFolder: String
            get() = if (Pref.useInternal) "${type.file}:${Pref.downloadLocation}"
            else "${type.uri}:${Pref.downloadLocation}"
        val HOME_PAGE = if (BuildConfig.DEBUG) "http://localhost:1234" else "https://www.google.com"
    }
}