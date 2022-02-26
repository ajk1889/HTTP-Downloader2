package resonance.http.httpdownloader.implementations

import android.content.Context
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.configsFolder
import resonance.http.httpdownloader.helpers.extIconMap
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.*

class TransferWrapper(
    private val data: JSONObject,
    var id: Long = data.optLong(C.dt.id),
    var isServiceMode: Boolean = false
) {
    companion object {
        val inParallel: String get() = "aW4gcGFyYWxsZWwu"
        fun loadTransfers(
            ctx: Context,
            init: ((TransferWrapper) -> Unit)? = null
        ): MutableList<TransferWrapper> {
            val list = mutableListOf<TransferWrapper>()
            val files = configsFolder(ctx).listFiles() ?: return list
            log(
                "TransferWrapper",
                "loadTransfers: started to load transfers count(<=${files.size})"
            )
            for (f in files) {
                if (f.length() > 50.KB) continue
                val transfer = read(f) ?: continue
                list.add(TransferWrapper(transfer).apply {
                    if (!hasSucceeded) isStopped = true
                    if (scheduledTime < now() && statusIcon == C.ico.scheduled)
                        statusIcon = C.ico.blank
                    init?.invoke(this)
                })
            }
            log("TransferWrapper", "loadTransfers: loaded ${list.size} transfers")
            return list
        }

        fun loadTransfer(ctx: Context, id: Long): TransferWrapper {
            val file = File(configsFolder(ctx), "$id.json")
            val data = read(file)
                ?: throw FileNotFoundException("The file with id=$id is invalid or doesn't exist")
            return TransferWrapper(data)
        }

        private fun read(configFile: File): JSONObject? = {
            val ip = FileInputStream(configFile)
            val bytes = ByteArray(ip.available())
            ip.read(bytes)
            JSONObject(String(bytes).trim())
        } otherwise null

        private fun write(
            id: Long,
            text: String,
            ctx: Context,
            ignoreNonExistence: Boolean
        ) = {
            val configFile = File(configsFolder(ctx), "$id.json")
            if (ignoreNonExistence || configFile.exists()) {
                val op = FileOutputStream(configFile)
                op.write(text.toByteArray())
                op.flush(); op.close()
                true
            } else false
        } otherwise false
    }

    fun updateCoreData(ctx: Context, coreData: JSONObject? = null) {
        val newData = coreData ?: read(File(configsFolder(ctx), "$id.json")) ?: return
        if (newData.has(C.dt.url))
            data.put(C.dt.url, newData.getString(C.dt.url))
        if (newData.has(C.dt.headers))
            data.put(C.dt.headers, newData.getString(C.dt.headers))
        if (newData.has(C.dt.offset))
            data.put(C.dt.offset, newData.getString(C.dt.offset))
        if (newData.has(C.dt.limit))
            data.put(C.dt.limit, newData.getString(C.dt.limit))
        //not adding fileName & emptyFirst mode for safety
    }

    private val map = BlockingMap<String, Any?>()
    fun dontHaveChanges() = map.isEmpty()
    var fileName: String = data.optString(C.dt.fileName, "Unknown URL")
        set(value) {
            if (title == "") title = value
            field = value
        }

    val url get() = data.optString(C.dt.url, "")
    val icon: Int
        get() = extIconMap[
                fileName.toNameNExtension().second?.toLowerCase(Locale.getDefault())
        ] ?: R.drawable.download
    val isVideoOrAudio: Boolean
        get() {
            val extension = fileName.toNameNExtension().second?.toLowerCase(Locale.getDefault())
            return extension in setOf(
                "mp4", "mkv", "mov", "wmv", "webm", "mpeg", "avi",
                "flv", "mpg", "ogg", "mp3", "aiff", "aac", "wma", "wav"
            )
        }
    val type: String = data.optString(C.dt.type, null)
    val emptyFirstMode = data.optBoolean(C.dt.emptyFirstMode, false)
    val lastUrl: String = data.optString(C.dt.lastUrl, C.HOME_PAGE)

    val lastUpdatesJson: JSONObject
        get() {
            val mapCopy = map.copyToMap()
            silently { map.clear() }
            return if (isServiceMode)
                JSONObject(mapCopy).also { it.put(C.dt.id, id) }
            else getAbsoluteJson()
        }

    var title: String = data.optString(C.dt.title, fileName)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.title] = value
        }

    //folder to which download & configuration file will be saved; starts with either 'file:' or 'uri:'
    var outputFolder: String =
        data.optString(C.dt.outputFolder, "${C.type.file}:${C.INTERNAL_DOWNLOAD_FOLDER}")
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.outputFolder] = outputFolder
        }

    var maxSpeed = data.optInt(C.dt.maxSpeed, Int.MAX_VALUE)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.maxSpeed] = value
        }
    var scheduledTime = data.optLong(C.dt.scheduledTime, -1)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.scheduledTime] = value
        }
    var statusIcon: String = data.optString(C.dt.statusIcon, C.ico.blank)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.statusIcon] = statusIcon
        }
    var pauseBtnIcon: String = data.optString(C.dt.pauseBtnIcon, C.ico.pause)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.pauseBtnIcon] = pauseBtnIcon
        }
    var stopBtnIcon: String = data.optString(C.dt.stopBtnIcon, C.ico.stop)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.stopBtnIcon] = stopBtnIcon
        }

    var animateBtnsSetTime = 0L //need not be converted to json
    var shouldAnimateBtns: Boolean = data.optBoolean(C.dt.shouldAnimateBtns, false)
        set(value) {
            if (isServiceMode) throw RuntimeException("${C.dt.shouldAnimateBtns} cannot be modified from service")
            if (value) animateBtnsSetTime = now()
            field = value
        }
    var isStreamServer: Boolean = data.optBoolean(C.dt.isStreamServer, false)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.isStreamServer] = value
        }
    var isCollapsed: Boolean = data.optBoolean(C.dt.isCollapsed, true)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.isCollapsed] = value
        }

    val isOngoing: Boolean get() = !(isPaused || isStopped || hasFailed || hasSucceeded)

    var isStopped: Boolean = data.optBoolean(C.dt.isStopped, false)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.isStopped] = isStopped
        }
    var isPaused: Boolean = data.optBoolean(C.dt.isPaused, false)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.isPaused] = isPaused
        }
    var contentLengthType: Boolean = data.optBoolean(C.dt.contentLengthType, false)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.contentLengthType] = value
        }

    //need not communicate details of these members to json
    var transfer: Transfer? = null
    lateinit var outputObject: OutputObj

    var hasSucceeded: Boolean = data.optBoolean(C.dt.hasSucceeded, false)
        set(value) {
            field = value
            if (hasSucceeded) hasFailed = false
            if (isServiceMode) map[C.dt.hasSucceeded] = hasSucceeded
        }
    var hasFailed: Boolean = data.optBoolean(C.dt.hasFailed, false)
        set(value) {
            field = value
            if (hasFailed) hasSucceeded = false
            if (isServiceMode) map[C.dt.hasFailed] = hasFailed
        }
    var isStatusProgressVisible = data.optBoolean(C.dt.isStatusProgressVisible, false)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.isStatusProgressVisible] =
                isStatusProgressVisible
        }

    var written: Long = data.optLong(C.dt.written, 0)
        get() = if (emptyFirstMode) field else -1
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.written] = written
        }
    var progress: Int = data.optInt(C.dt.progress, 0)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.progress] = progress
        }
    var percent: String = data.optString(C.dt.percent, "---")
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.percent] = percent
        }
    var reach: String = data.optString(C.dt.reach, "---")
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.reach] = reach
        }
    var speed: String = "---"
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.speed] = speed
        }
    var downloaded: String = data.optString(C.dt.downloaded, "Downloaded --- out of ---")
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.downloaded] = downloaded
        }
    var weight0: Double = data.optDouble(C.dt.weight0, 0.0)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.weight0] = weight0
        }
    var weight1: Double = data.optDouble(C.dt.weight1, 100.0)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.weight1] = weight1
        }
    var weight2: Double = 100.0 - weight0 - weight1
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.weight2] = weight2
        }
    var offset: String = formatSize(data.optLong(C.dt.offset, 0))
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.offset] = offset
        }
    var limit: String = formatSize(data.optLong(C.dt.limit, -1))
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.limit] = limit
        }
    var onEndAction: String? = data.optString(C.dt.onEndAction, null)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.onEndAction] = onEndAction
        }
    var startAfter: String? = data.optString(C.dt.startAfter, null)
        set(value) {
            field = value
            if (isServiceMode) map[C.dt.startAfter] = startAfter
        }

    var exceptionCause: String? = data.optString(C.dt.exceptionCause, null)
        set(value) {
            field = value
            if (value != null) statusIcon = C.ico.warning
            if (isServiceMode) map[C.dt.exceptionCause] = exceptionCause
        }

    fun saveState(ctx: Context, ignoreNonExistence: Boolean = false) =
        write(id, getAbsoluteJson().str, ctx, ignoreNonExistence)

    fun computeWeights(transfer: Transfer) {
        offset = formatSize(transfer.offset)
        limit = formatSize(transfer.limit)
        with(transfer) {
            weight0 = offset * 100.0 / total
            weight2 = (total - (if (limit == -1L) total else limit)) * 100.0 / total
            weight1 = 100 - weight2 - weight0
        }
    }

    private val bytesReadLog = LinkedList<BytesAtInstant>()
    fun updateTaskProgress(shouldUpdateSpeed: Boolean = true) {
        transfer?.let { transfer ->
            //'written' should be modified only when file is downloading & not when empty file is being created;
            if (emptyFirstMode && !transfer.isCreatingEmptyFile)
                written = transfer.written

            progress = transfer.progressOfThisDownload
            percent = transfer.getProgressOfThisDownload() + "%"
            with(transfer.offset + transfer.writeTotal + transfer.readTotal) {
                reach = formatSize(this, 2) + " / " +
                        formatSize(transfer.total, 2)
            }
            if (shouldUpdateSpeed) speed = transfer.getSpeed(1)
            val totalReach = transfer.writeTotal + transfer.readTotal
            val totalSize = transfer.stopReadAt + transfer.writeTotal
            val now = now()
            downloaded = formatSize(totalReach, 2) + " / " + formatSize(totalSize, 2)
            if (transfer.isCreatingEmptyFile) downloaded = "Written $downloaded"
            if (bytesReadLog.isEmpty() || bytesReadLog.last.timeStamp + 1.seconds < now)
                bytesReadLog.add(totalReach at now)
            bytesReadLog.removeUntil { it.timeStamp + 1.minutes < now }
            if (bytesReadLog.size > 1) {
                val timeDiff = now - bytesReadLog.first.timeStamp
                val bytesDiff = totalReach - bytesReadLog.first.bytesRead
                val timeRemaining = if (bytesDiff > 0)
                    formatTimeDetailed((totalSize - totalReach) * timeDiff / bytesDiff)
                else ""
                if (timeRemaining != "") downloaded += " | Remaining: $timeRemaining"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is TransferWrapper && other.id == id
    }

    override fun hashCode(): Int {
        return id.toInt()
    }

    fun getAbsoluteJson(): JSONObject {
        val obj = data
        obj.put(C.dt.title, title)
        obj.put(C.dt.written, written)
        obj.put(C.dt.outputFolder, outputFolder)
        obj.put(C.dt.progress, progress)
        obj.put(C.dt.statusIcon, statusIcon)
        obj.put(C.dt.percent, percent)
        obj.put(C.dt.reach, reach)
        obj.put(C.dt.downloaded, downloaded)
        obj.put(C.dt.weight0, if (weight0.isNaN()) 0 else weight0)
        obj.put(C.dt.weight1, if (weight1.isNaN()) 100 else weight1)
        obj.put(C.dt.fileName, fileName)
        obj.put(C.dt.id, id)
        obj.put(C.dt.isStatusProgressVisible, isStatusProgressVisible)
        obj.put(C.dt.shouldAnimateBtns, shouldAnimateBtns)
        obj.put(C.dt.exceptionCause, exceptionCause)
        obj.put(C.dt.isCollapsed, isCollapsed)
        obj.put(C.dt.isStreamServer, isStreamServer)
        obj.put(C.dt.maxSpeed, maxSpeed)
        obj.put(C.dt.scheduledTime, scheduledTime)
        obj.put(C.dt.stopBtnIcon, stopBtnIcon)
        obj.put(C.dt.pauseBtnIcon, pauseBtnIcon)
        obj.put(C.dt.isPaused, isPaused)
        obj.put(C.dt.contentLengthType, contentLengthType)
        obj.put(C.dt.isStopped, isStopped)
        obj.put(C.dt.hasSucceeded, hasSucceeded)
        obj.put(C.dt.hasFailed, hasFailed)
        obj.put(C.dt.onEndAction, onEndAction)
        obj.put(C.dt.startAfter, startAfter)
        return obj
    }

    fun getEssentialJSON() = {
        JSONObject().apply {
            put(C.dt.url, data.getString(C.dt.url))
            if (data.has(C.dt.headers))
                put(C.dt.headers, data.getString(C.dt.headers))
            if (data.has(C.dt.offset))
                put(C.dt.offset, data.getLong(C.dt.offset))
            if (data.has(C.dt.limit))
                put(C.dt.limit, data.getLong(C.dt.limit))
            put(C.dt.type, data.optString(C.dt.type, DownloadObject.TYPE))
            put(C.dt.emptyFirstMode, data.optBoolean(C.dt.emptyFirstMode, false))
            put(C.dt.fileName, data.optString(C.dt.fileName, "file"))
        }
    } otherwise null

    fun copyContentsFrom(task: JSONObject) {
        if (task.has(C.dt.title)) title = task.getString(C.dt.title)
        if (task.has(C.dt.pauseBtnIcon)) pauseBtnIcon = task.getString(C.dt.pauseBtnIcon)
        if (task.has(C.dt.statusIcon)) statusIcon = task.getString(C.dt.statusIcon)
        if (task.has(C.dt.stopBtnIcon)) stopBtnIcon = task.getString(C.dt.stopBtnIcon)
        if (task.has(C.dt.shouldAnimateBtns))
            shouldAnimateBtns = task.getBoolean(C.dt.shouldAnimateBtns)
        if (task.has(C.dt.isStopped)) isStopped = task.getBoolean(C.dt.isStopped)
        if (task.has(C.dt.isPaused)) isPaused = task.getBoolean(C.dt.isPaused)
        if (task.has(C.dt.contentLengthType))
            contentLengthType = task.getBoolean(C.dt.contentLengthType)
        if (task.has(C.dt.hasSucceeded)) hasSucceeded = task.getBoolean(C.dt.hasSucceeded)
        if (task.has(C.dt.hasFailed)) hasFailed = task.getBoolean(C.dt.hasFailed)
        if (task.has(C.dt.isCollapsed)) isCollapsed = task.getBoolean(C.dt.isCollapsed)
        if (task.has(C.dt.isStreamServer)) isStreamServer = task.getBoolean(C.dt.isStreamServer)
        if (task.has(C.dt.maxSpeed)) maxSpeed = task.getInt(C.dt.maxSpeed)
        if (task.has(C.dt.scheduledTime)) scheduledTime = task.getLong(C.dt.scheduledTime)
        if (task.has(C.dt.isStatusProgressVisible))
            isStatusProgressVisible = task.getBoolean(C.dt.isStatusProgressVisible)
        if (task.has(C.dt.progress)) progress = task.getInt(C.dt.progress)
        if (task.has(C.dt.written)) written = task.getLong(C.dt.written)
        if (task.has(C.dt.percent)) percent = task.getString(C.dt.percent)
        if (task.has(C.dt.reach)) reach = task.getString(C.dt.reach)
        if (task.has(C.dt.speed)) speed = task.getString(C.dt.speed)
        if (task.has(C.dt.downloaded)) downloaded = task.getString(C.dt.downloaded)
        if (task.has(C.dt.weight0)) weight0 = task.getDouble(C.dt.weight0)
        if (task.has(C.dt.weight1)) weight1 = task.getDouble(C.dt.weight1)
        if (task.has(C.dt.weight2)) weight2 = task.getDouble(C.dt.weight2)
        if (task.has(C.dt.offset)) offset = task.getString(C.dt.offset)
        if (task.has(C.dt.limit)) limit = task.getString(C.dt.limit)
        if (task.has(C.dt.onEndAction)) onEndAction = task.getString(C.dt.onEndAction)
        if (task.has(C.dt.startAfter)) startAfter = task.getString(C.dt.startAfter)
        if (task.has(C.dt.exceptionCause)) exceptionCause = task.getString(C.dt.exceptionCause)
        if (task.has(C.dt.outputFolder)) outputFolder = task.getString(C.dt.outputFolder)
    }

    // When transferWrappers are loaded from json, their corresponding transfers will not be initialised.
    // So it is not possible to define transfer.startNextDownload at the time of loading.
    // onEndInitializer is defined at time of loading & invoked during transfer initialization;
    // so they can be used to define transfer.startNextDownload at the time of transfer initialization
    var onEndInitializer: ((transfer: Transfer, action: String) -> Unit)? = null

    fun initializeTransfer(): Transfer {
        transfer?.source?.close()
        transfer?.destination?.close()
        with(DownloadObject(data)) {
            localSpeedLimit = maxSpeed
            transfer = this
            if (onEndAction != null && isServiceMode)
                onEndInitializer!!.invoke(this, onEndAction!!)
            return this
        }
    }

    fun makeNull() {
        transfer?.stop()
        transfer = null
    }

    override fun toString(): String {
        return "TransferWrapper: $id"
    }
}