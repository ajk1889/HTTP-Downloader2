package resonance.http.httpdownloader.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.FileJoiner
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.id
import resonance.http.httpdownloader.implementations.UriFile
import java.io.InputStream
import java.io.OutputStream
import java.lang.Thread.sleep
import kotlin.concurrent.thread

class JoinerService : Service() {
    companion object {
        private const val CHANNEL_ID = "fileJoiner"
        private const val NOTIFICATION_ID = 2832
        private const val REQUEST_CODE = 7831
        @Volatile
        var isRunning = false
        var lastBroadcast: Intent? = null
        val toAdd: String get() = "dG8gYWRkIA=="
        var files: List<UriFile> = emptyList()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    init {
        files = emptyList()
    }

    private lateinit var notificationManager: NotificationManager
    private var totalSize = 0L
    private var deleteAfterJoin = false
    private var appendToFirst = false
    private var removeNotification = true

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sup = super.onStartCommand(intent, flags, startId)
        if (files.isNotEmpty() || intent == null || isRunning) return sup
        val data = JSONObject(intent.getStringExtra(C.misc.fileJoinerDataJSON))
        if (data.getString(C.dt.type) != JoinerObject.TYPE) return sup

        isRunning = true
        deleteAfterJoin = data.getBoolean(C.joiner.deleteAfterJoin)
        appendToFirst = data.getString(C.joiner.output) == C.joiner.first

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannel()
        startForeground(NOTIFICATION_ID, initialNotification())

        log(
            "JoinerService",
            "onStartCommand: called",
            "appendToFirst=$appendToFirst; deleteAfterJoin=$deleteAfterJoin"
        )

        try {
            files = data.getJSONArray(C.joiner.inputs).run {
                List(length()) { index ->
                    UriFile(this@JoinerService, getString(index)).also { totalSize += it.size }
                }
            }
            val output: UriFile
            if (appendToFirst) {
                output = files[0]
                totalSize -= output.size
                files = files.subList(1, files.size)
            } else output = UriFile(this, data.getString(C.joiner.output))
            startJoining(
                Array(files.size) { files[it].getInputStream() },
                output.getOutputStream(appendToFirst)
            )
            startProgressMonitor()
        } catch (e: Exception) {
            log("JoinerService", "onStartCommand: ", e)
            val failedIntent = Intent(C.filter.JOINING_FAILED)
            failedIntent.putExtra("exception", e.message)
            failedIntent.id = ++ApplicationClass.lastSentIntentId
            lastBroadcast = failedIntent
            LocalBroadcastManager.getInstance(this).sendBroadcast(failedIntent)
            stopSelf()
        }

        return sup
    }

    lateinit var joiner: JoinerObject
    val handler = Handler()
    private fun startJoining(inputs: Array<InputStream>, output: OutputStream) {
        log("JoinerService", "startJoining: ")
        joiner = JoinerObject(inputs, output)
        joiner.onPartComplete = {
            if (deleteAfterJoin)
                DocumentsContract.deleteDocument(contentResolver, files[it].uri)
        }
        joiner.onFailed = { exception ->
            isRunning = false
            log("JoinerService", "startJoining: ", exception)
            val intent = Intent(C.filter.JOINING_FAILED)
            val cause = getExceptionCause(exception, "joiner")
            intent.putExtra("exception", cause)
            intent.id = ++ApplicationClass.lastSentIntentId
            lastBroadcast = intent
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopSelf()
            handler.postDelayed({
                builder.setProgress(0, 0, false)
                    .setContentTitle("Joining failed")
                    .setContentText(cause)
                    .setOngoing(false)
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }, 300)
        }
        joiner.onComplete = {
            isRunning = false
            log("JoinerService", "startJoining: joining completed")
            val count = files.size
            sendProgressBroadCast()
            removeNotification = false
            stopSelf()

            handler.postDelayed({
                builder.setProgress(0, 0, false)
                    .setContentTitle("Joined $count files")
                    .setContentText("Speed:${joiner.getSpeed(1)}")
                    .setOngoing(false)
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }, 300)
        }
        joiner.startJoining()
    }

    private fun startProgressMonitor() = thread {
        silently {
            while (isRunning) {
                sendProgressBroadCast()
                updateNotification()
                sleep(300)
            }
        }
    }

    private fun sendProgressBroadCast() {
        val intent = Intent(C.filter.JOINER_PROGRESS)
        intent.putExtra(C.joiner.totalProgress, (joiner.reached * 100) / totalSize)
        intent.putExtra(C.joiner.totalSize, totalSize)
        intent.putExtra(C.joiner.totalReach, joiner.reached)
        intent.putExtra(C.joiner.speed, joiner.getSpeed(1))

        val currentSize = files[joiner.currentPart].size
        var currentReach = joiner.reached
        for (i in 0 until joiner.currentPart)
            currentReach -= files[i].size

        if (appendToFirst)
            intent.putExtra(C.joiner.currentPart, joiner.currentPart + 1)
        else intent.putExtra(C.joiner.currentPart, joiner.currentPart)

        intent.putExtra(C.joiner.currentPartSize, currentSize)
        intent.putExtra(C.joiner.currentReach, currentReach)
        intent.putExtra(C.joiner.partProgress, (currentReach * 100) / currentSize)
        intent.id = ++ApplicationClass.lastSentIntentId
        lastBroadcast = intent
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification() {
        val progress = (joiner.reached * 100) / totalSize
        builder.setProgress(100, progress.toInt(), false)
            .setContentTitle("Joined ${joiner.currentPart}/${files.size} parts")
            .setContentText(
                "Progress: $progress% (${formatSize(joiner.reached)}/${formatSize(
                    totalSize
                )}) | Speed: ${joiner.getSpeed()}"
            )
        if (isRunning) notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    lateinit var builder: NotificationCompat.Builder
    private fun initialNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            Intent(this, FileJoiner::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setProgress(100, 0, false)
            .setContentTitle("Starting file joiner")
            .setContentIntent(pending)
            .setContentText("Initializing...")
            .setSmallIcon(R.drawable.joiner_icon)
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Joiner",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Download service running"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (::joiner.isInitialized) joiner.stop()
        files = emptyList()
        isRunning = false
        stopForeground(removeNotification)
        super.onDestroy()
        log("JoinerService", "onDestroy: ")
    }
}
