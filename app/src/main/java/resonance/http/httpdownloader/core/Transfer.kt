package resonance.http.httpdownloader.core

import resonance.http.httpdownloader.helpers.Pref
import java.io.IOException
import java.lang.Thread.sleep
import kotlin.concurrent.thread
import kotlin.math.min

open class Transfer(val id: Long) {
    companion object {
        private const val sectionTimeMs = 33/*ms*/
        var progressUpdateInterval = 300L
        fun setInterval(function: () -> Unit, runWhile: () -> Boolean) {
            thread {
                while (runWhile()) {
                    function()
                    sleep(progressUpdateInterval)
                }
            }
        }

        val ongoing = mutableListOf<Transfer>()
        var globalSpeedLimit = Pref.speedLimit
            set(value) {
                field = value
                ongoing.forEach { it.readLimitPerTimeSection = it.computeReadLimit() }
            }
    }

    override fun equals(other: Any?): Boolean {
        return other is Transfer && other.id == this.id
    }

    override fun hashCode(): Int {
        return this.id.toInt()
    }

    var localSpeedLimit = Int.MAX_VALUE
        set(value) {
            field = value
            readLimitPerTimeSection = computeReadLimit()
        }
    //Transfer streams
    lateinit var sourceRefresh: () -> InputObj //should provide an inputStream pre-seeked to required byte position
    var source: InputObj? = null
    private val sourceNN: InputObj
        get() {
            return if (source == null) {
                log("Transfer", "sourceNN: Source is null; invoking sourceRefresh")
                sourceRefresh().also { source = it }
            } else source!!
        }

    lateinit var destRefresh: () -> OutputObj
    var destination: OutputObj? = null
    private val destinationNN: OutputObj
        get() {
            return if (destination == null) {
                log("Transfer", "destinationNN: Destination is null; invoking destRefresh")
                destRefresh().also { destination = it }
            } else destination!!
        }

    var offset = 0L
    var limit = -1L
    var total = -1L //total file size
    var written = 0L //this is the byte position to start writing from, when using an empty file

    //writeTotal indicates the size of destination file. This is added to offset when making request
    //for requesting bytes after ending of downloaded file, so that the file can be appended
    var writeTotal = 0L
        set(value) {
            written = value; field = value
        }

    var onStart: ((obj: Transfer) -> Unit)? =
        null //functions like starting progress reporting thread should be started here
    var onStartIO: (() -> Unit)? = null
    var onStop: ((obj: Transfer) -> Unit)? = null //functions like updating final progress & status
    var onPause: ((obj: Transfer) -> Unit)? = null  //gets called from working thread
    var onResume: ((obj: Transfer) -> Unit)? = null //gets called from working thread
    var onFailed: ((reason: String) -> Unit)? =
        null //functions like closing resources should be done here
    var onSuccess: ((obj: Transfer) -> Unit)? =
        null //functions like closing resources should be done here too
    var onRemovedFromOngoing: (() -> Unit)? = null
    var whileRunning: ((obj: Transfer) -> Unit)? =
        null //gets called every time a buffer is read from source
    var onWriteFailed: ((Transfer, Exception, Int) -> Unit)? =
        null //to be called when some exceptions happen during read
    var onReadFailed: ((Transfer, Exception, Int) -> Unit)? =
        null //to be called when some exceptions happen during write
    var onWriteFailedExceptionHandler: ((Transfer, Exception, Int) -> Unit)? =
        null //called when exception happen during handling exception on read
    var onReadFailedExceptionHandler: ((Transfer, Exception, Int) -> Unit)? =
        null //called when exception happen during handling exception on write

    var onEndAction: String? = null
    var startNextDownload: ((id: Long) -> Unit)? = null

    private var shouldStop = false
    var shouldCreateEmptyFile = false
    var isPaused = false //to sleep thread when user pauses operation
    private var wasPaused = false //to know whether
    @Volatile
    var readTotal = 0L
    @Volatile
    var stopReadAt = 0L

    var maxRetries = 10
        set(value) {
            retriesRemaining = value; field = value
        }

    private fun computeReadLimit() = if (localSpeedLimit == Int.MAX_VALUE)
        globalSpeedLimit / (1000 / sectionTimeMs)
    else localSpeedLimit / (1000 / sectionTimeMs)

    private var readLimitPerTimeSection = computeReadLimit()

    private var retriesRemaining = 10
    /*this method retries failingTasks 10 times.
    If the function succeeds to run, retriesRemaining is reset to 10
    Whenever it throws an exception, exceptionHandler [like waiting for 10 seconds] is invoked*/
    private fun runWithRetry(
        failingTask: () -> Unit,
        exceptionHandler: ((e: Exception, retries: Int) -> Unit),
        exceptionHandler2: ((e: Exception, retries: Int) -> Unit)? = null
    ) {
        var reason = ""
        while (retriesRemaining-- > 0) {
            try {
                failingTask()
                retriesRemaining = maxRetries
                break
            } catch (e: Exception) {
                if (retriesRemaining == 0) reason = getExceptionCause(e, "runWithRetry")
                if (e !is IOException || e.message?.contains("File descriptor closed") != true)
                    sleep(100)

                try {
                    exceptionHandler.invoke(e, retriesRemaining)
                } catch (e2: Exception) {
                    log(
                        "Transfer",
                        "runWithRetry: caught exception $e2 while handling $e(retriesRemaining=$retriesRemaining)",
                        "Stacktrace=", e2
                    )
                    exceptionHandler2?.invoke(e2, retriesRemaining)
                }
            }
        }
        if (retriesRemaining == -1) {
            log("Transfer", "runWithRetry: retryCount exhausted; invoking onFailed")
            onFailed?.invoke(reason)
            executeAction(onEndAction)
            stopTime = now(); failedTime = stopTime
            isRunning = false
        }
    }

    fun executeAction(action: String?) {
        if (action == null) return
        if (action.startsWith("start ")) {
            with(startNextDownload) {
                if (this == null)
                    throw RuntimeException("startNextDownload function not defined for onEndAction: $action")
                else invoke(action.substring(6).toLong())
            }
        } else throw RuntimeException("Unknown onEndAction $action")
    }

    private fun read(b: ByteArray, len: Int = Int.MAX_VALUE): Int {
        var bytesRead: Int = -1
        val count = min(len, min(stopReadAt - readTotal, b.size.toLong()).toInt())
        runWithRetry(
            { bytesRead = sourceNN.read(b, count) },
            { e, retriesRemaining ->
                source?.close()
                source = sourceRefresh()
                onReadFailed?.invoke(this, e, retriesRemaining)
            },
            { e, retriesRemaining ->
                onReadFailedExceptionHandler?.invoke(this, e, retriesRemaining)
            }
        )
        return bytesRead
    }

    private fun write(b: ByteArray, len: Int) {
        runWithRetry(
            { destinationNN.write(b, len) },
            { e, retriesRemaining ->
                destination?.close()
                destination = destRefresh()
                onWriteFailed?.invoke(this, e, retriesRemaining)
            },
            { e, retriesRemaining ->
                onWriteFailedExceptionHandler?.invoke(this, e, retriesRemaining)
            }
        )
    }

    var isRunning = false
    var startedTime = Long.MAX_VALUE
    var stopTime = Long.MAX_VALUE
    var succeededTime = Long.MAX_VALUE
    var failedTime = Long.MAX_VALUE
    var pauseTime = Long.MAX_VALUE
    var resumeTime = Long.MAX_VALUE

    var isTransferRunning = false
    var transferThread: Thread? = null

    var thisSectionReadCount = 0
    var thisTimeSectionEndTime = 0L

    @Synchronized
    open fun startTransfer() {
        // validating existence of another transfer with same id (Previous transfer failed to stop)
        if (this in ongoing) {
            log("Transfer", "startTransfer: This transfer is already running")
            // Since checking is ID based object in list may be different by reference;
            // So need to validate item in list & not `this`
            val index = ongoing.indexOf(this)
            val duplicate = ongoing[index]
            if (duplicate.shouldStop) {
                if (duplicate.transferThread?.isAlive == true) {
                    log(
                        "Transfer",
                        "startTransfer: Previous transfer is stopping.",
                        "waiting for it to complete"
                    )
                    duplicate.onRemovedFromOngoing = {
                        log(
                            "Transfer",
                            "startTransfer: Previous one removed from ongoing (stopped).",
                            "Starting transfer"
                        )
                        this.startTransfer()
                    }
                    return
                } else {
                    log("Transfer", "startTransfer: dead duplicate exist. Removing & proceeding")
                    ongoing.remove(duplicate)
                    ongoing.add(this)
                }
            } else {
                duplicate.stop()
                throw RuntimeException("This transfer is already running")
            }
        } else ongoing.add(this)


        stopReadAt = when {
            (limit != -1L) -> limit - offset - writeTotal
            (total != -1L) -> total - offset - writeTotal
            else -> throw RuntimeException("'limit' or 'total' need to be set before starting download")
        }
        log("Transfer", "startTransfer: stopReadAt=$stopReadAt=${formatSize(stopReadAt)}")
        onStart?.invoke(this)
        prevTime = now() //to calculate speed
        shouldStop = false
        readTotal = 0; prevReadBytes = 0

        transferThread = thread {
            try {
                isRunning = true; startedTime = now()

                var bytesRead = -1
                val buffer = if (stopReadAt < 100.KB)
                    ByteArray(min(readLimitPerTimeSection, stopReadAt.toInt()))
                else ByteArray(min(readLimitPerTimeSection, 100.KB))
                log(
                    "Transfer",
                    "startTransfer: buffer=${buffer.size}=${formatSize(buffer.size.toLong())}"
                )

                isTransferRunning = true
                onStartIO?.invoke()
                try {
                    if (shouldCreateEmptyFile && destinationNN.length() != stopReadAt + writeTotal) {
                        log(
                            "Transfer",
                            "startTransfer: createEmptyFile",
                            "destinationNN.length() [${destinationNN.length()} = ${formatSize(
                                destinationNN.length()
                            )}] != ",
                            "stopReadAt+writeTotal [$stopReadAt + $writeTotal = ${formatSize(
                                stopReadAt + writeTotal
                            )}]",
                            "making an empty file"
                        )
                        createEmptyFile(stopReadAt + writeTotal)
                    }

                    bytesRead = read(buffer)
                    log(
                        "Transfer",
                        "startTransfer: Initial read to buffer len=",
                        "$bytesRead=${formatSize(bytesRead.toLong())}"
                    )
                } catch (e: RuntimeException) {
                    //If fileObject is not accessible, stop here
                    log("Transfer", "startTransfer: exception", e)
                    onFailed?.invoke("File could not be accessed")
                    executeAction(onEndAction)
                    stopTime = now(); failedTime = stopTime
                    isRunning = false

                    // to avoid going to download loop;
                    // don't use return, there are statements to be executed
                    shouldStop = true
                }

                printHyperParams()
                while (bytesRead != -1 && !shouldStop) {
                    whileRunning?.invoke(this)

                    //to let thread sleep after completing all pending jobs, condition checks for wasPaused also
                    if (isPaused && wasPaused) {
                        sleep(200); continue
                    }

                    readTotal += bytesRead //updating status
                    write(buffer, bytesRead)
                    //updating seek position in empty file; To be used on pause & resume only
                    written = readTotal + writeTotal
                    if (readTotal == stopReadAt) {
                        log(
                            "Transfer",
                            "startTransfer: readTotal = stopReadAt",
                            "$readTotal = ${formatSize(readTotal)}",
                            "Stopping download"
                        )
                        break //completed download
                    }

                    val now = preciseNow()
                    if (thisTimeSectionEndTime < now) {
                        thisTimeSectionEndTime = now + sectionTimeMs * 1_000_000L
                        thisSectionReadCount = 0
                    }
                    thisSectionReadCount += bytesRead
                    if (thisSectionReadCount >= readLimitPerTimeSection) {
                        val sleepTime = thisTimeSectionEndTime - now
                        sleep(sleepTime / 1_000_000L, (sleepTime % 1_000_000L).toInt())
                        thisTimeSectionEndTime += sectionTimeMs * 1_000_000L
                        thisSectionReadCount -= readLimitPerTimeSection
                    }
                    // to avoid multi-threading errors
                    var diff = readLimitPerTimeSection - thisSectionReadCount
                    if (diff < 1) {
                        thisSectionReadCount = 0; diff = 1
                    }


                    bytesRead = read(buffer, diff)

                    //handling pauses
                    if (isPaused) {
                        if (!wasPaused) {
                            pauseTime = now()
                            log("Transfer", "startTransfer: transfer paused; invoking onPause")
                            onPause?.invoke(this)

                            //writing everything that is already read, to avoid corruption
                            readTotal += bytesRead
                            write(buffer, bytesRead)
                            //updating seek position in empty file; To be used on pause & resume only
                            written = readTotal + writeTotal
                            log(
                                "Transfer",
                                "startTransfer: Written remaining $bytesRead=${formatSize(bytesRead.toLong())}"
                            )
                            wasPaused = true
                        }
                    } else if (wasPaused) {
                        resumeTime = now()
                        log("Transfer", "startTransfer: transfer resumed; invoking onResume")
                        onResume?.invoke(this)
                        wasPaused = false
                    }
                }

                isTransferRunning = false

                when {
                    shouldStop -> {
                        log("Transfer", "startTransfer: shouldStop = true; invoking onStop")
                        onStop?.invoke(this)
                    }
                    failedTime > now() -> { //i.e. hasn't failed yet
                        log("Transfer", "startTransfer: Completed; invoking onSuccess")
                        onSuccess?.invoke(this)
                        executeAction(onEndAction)
                        succeededTime = now()
                    }
                    else -> log("Transfer", "startTransfer: failure")
                }

                isRunning = false; stopTime = now()
                silently { source?.close() }
                silently { destination?.close() }
                log("Transfer", "startTransfer: thread stopped")
            } catch (e: Exception) {
                //If fileObject is not accessible, stop here
                log("Transfer", "startTransfer: exception", e)
                onFailed?.invoke(getExceptionCause(e, "Transfer.startTransfer"))
                executeAction(onEndAction)
                stopTime = now(); failedTime = stopTime
                isRunning = false

                // to avoid going to download loop;
                // don't use return, there are statements to be executed
                shouldStop = true
                // Also finally need to be added to remove item from onGoing
            } finally {
                // Since checking is ID based object in list may be different by reference;
                // So need to execute onRemovedFromOngoing for item in list & not `this`
                synchronized(ongoing) {
                    val index = ongoing.indexOf(this)
                    if (index in ongoing.indices)
                        ongoing.removeAt(index).onRemovedFromOngoing?.invoke()
                }
            }
        }
    }

    var isCreatingEmptyFile = false
    private fun createEmptyFile(len: Long) {
        if (readTotal != 0L) throw RuntimeException("readTotal!=0 on beginning to create empty file")
        readTotal = destinationNN.length()
        log("Transfer", "createEmptyFile: len=$len; $readTotal bytes already exist")
        isCreatingEmptyFile = true
        val buffer = ByteArray(2.MB)
        while (readTotal != len && !shouldStop) {
            val n: Int = min(2.MB.toLong(), len - readTotal).toInt()
            write(buffer, n); readTotal += n
        }; destination?.close(); destination = null
        isCreatingEmptyFile = false
        readTotal = 0; prevReadBytes = 0 //resetting params to start download
        log("Transfer", "createEmptyFile: Done")
    }

    fun pause() {
        log("Transfer", "pause: called")
        isPaused = true
    }

    fun resume() {
        log("Transfer", "resume: called")
        isPaused = false
    }

    fun stop() {
        log("Transfer", "stop: called; Setting shouldStop=true")
        shouldStop = true
    }

    private var prevReadBytes = 0L
    private var prevTime = now()
    fun getSpeed(precision: Int = 0): String {
        val currTime = now()
        if (currTime <= prevTime) return "---"
        val speed =
            formatSize((readTotal - prevReadBytes) * 1000.0 / (currTime - prevTime), precision, " ")
        prevReadBytes = readTotal; prevTime = currTime
        return "$speed/s"
    }

    fun getSpeedBytes(): Long {
        val currTime = now()
        if (currTime <= prevTime) return 0L
        val speed = (readTotal - prevReadBytes) * 1000.0 / (currTime - prevTime)
        // Need not do: prevReadBytes = readTotal; prevTime = currTime
        // because getSpeed() will always be called after this function & values will get updated
        return speed.toLong()
    }

    val progressOfThisDownload: Int
        get() {
            return ((writeTotal + readTotal) * 100.0 / (writeTotal + stopReadAt)).toInt()
        }

    fun getProgressOfThisDownload(precision: Int = 2): String {
        val progress = (writeTotal + readTotal) * 100.0 / (writeTotal + stopReadAt)
        return if (progress.isNaN()) "0"
        else "%.${precision}f".format(progress)
    }

    val progressOfBytePosition: Int
        get() = ((offset + writeTotal + readTotal) * 100.0 / stopReadAt).toInt()

    fun getProgressOfBytePosition(precision: Int = 2): String =
        "%.${precision}f".format((offset + writeTotal + readTotal) * 100.0 / total)

    open fun printHyperParams() {
        val map = mapOf(
            "offset" to offset, "limit" to limit,
            "writeTotal" to writeTotal, "readTotal" to readTotal,
            "total" to total, "shouldCreateEmptyFile" to shouldCreateEmptyFile,
            "stopReadAt" to stopReadAt, "isCreatingEmptyFile" to isCreatingEmptyFile,
            "isRunning" to isRunning, "startedTime" to startedTime,
            "stopTime" to stopTime, "succeededTime" to succeededTime,
            "failedTime" to failedTime, "pauseTime" to pauseTime,
            "resumeTime" to resumeTime
        )
        log("Transfer", "printHyperParams: $map")
    }
}