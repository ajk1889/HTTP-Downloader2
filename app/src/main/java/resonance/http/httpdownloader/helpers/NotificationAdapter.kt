package resonance.http.httpdownloader.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.implementations.TransferWrapper
import resonance.http.httpdownloader.services.TransferService
import java.util.*
import kotlin.math.roundToInt

class NotificationAdapter(private val service: TransferService) {
    companion object {
        const val ONGOING_CHANNEL = "Ongoing_downloads"
        const val COMPLETED_CHANNEL = "Stopped_downloads"
        const val ONGOING_NOTIFICATION_ID = 2389
        const val COMPLETED_NOTIFICATION_ID = 4582
        const val REQUEST_CODE = 8712
    }

    private val notifier = service.notificationManager
    private val tasks = mutableSetOf<TransferWrapper>()
    private val ongoingBuilder: NotificationCompat.Builder by lazy {
        val pauseIntent = getPendingIntent(REQUEST_CODE, true)
        val appLaunch = getPendingIntent(REQUEST_CODE + 1)

        val builder = NotificationCompat.Builder(service, ONGOING_CHANNEL)
        builder.setSmallIcon(R.drawable.download)
        builder.setContentTitle("HTTP-Downloader is running")
        builder.addAction(R.drawable.pause, "Pause all", pauseIntent)
        builder.setContentIntent(appLaunch)
        builder.setContentText("Your download will start soon")
        builder.setOngoing(true)
        builder.priority = NotificationCompat.PRIORITY_DEFAULT
        builder
    }

    private val completedBuilder: NotificationCompat.Builder by lazy {
        val builder = NotificationCompat.Builder(service, COMPLETED_CHANNEL)
        builder.setSmallIcon(R.drawable.done2)
        builder.setContentIntent(getPendingIntent(REQUEST_CODE + 2))
        builder.setOngoing(false)
        builder.priority = NotificationCompat.PRIORITY_DEFAULT
        builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        builder
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoing = NotificationChannel(
                ONGOING_CHANNEL,
                "Ongoing downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Download service running"
                setSound(null, null)
            }
            val completed = NotificationChannel(
                COMPLETED_CHANNEL,
                "Completed downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Completed these tasks" }
            service.getSystemService(NotificationManager::class.java)?.also {
                it.createNotificationChannel(ongoing)
                it.createNotificationChannel(completed)
            }
        }
        notifier.cancel(COMPLETED_NOTIFICATION_ID)
    }

    var indefiniteProgressPosted = false
    private val bytesReadLog = LinkedList<BytesAtInstant>()
    fun showNotification(): Notification {
        var count = 0
        var lastOngoingTask: TransferWrapper? = null
        var totalSize = 1L //to avoid ArithmeticException
        var totalReach = 0L
        var totalSpeed = 0L

        tasks.toTypedArray().forEach {
            if (it.isOngoing) {
                count += 1
                it.transfer?.run {
                    totalSize += writeTotal + stopReadAt
                    totalReach += writeTotal + readTotal
                    totalSpeed += getSpeedBytes()
                }
                lastOngoingTask = it
            } else {
                tasks.remove(it)
                if (it.hasSucceeded || it.hasFailed) showCompletedNotification(it)
            }
        }
        return if (count == 0) {
            ongoingBuilder.setContentTitle("No downloads running")
                .setContentText("Stopping service...")
                .setProgress(0, 0, false)
                .build().also { notifier.notify(ONGOING_NOTIFICATION_ID, it) }
        } else {
            ongoingBuilder.setContentTitle(
                if (count == 1) "${lastOngoingTask?.fileName?.shorten(25)}"
                else "Downloading $count files"
            )

            val progress = totalReach * 100.0 / totalSize
            var status = if (progress > 99.99) "---"
            else "%.2f".format(progress) + "% | ${formatSize(totalSpeed, 1)}/s"

            if (Pref.showRemainingTimeInNotification) {
                val now = now()
                if (bytesReadLog.isEmpty() || bytesReadLog.last.timeStamp + 1.seconds < now)
                    bytesReadLog.add(totalReach at now)
                bytesReadLog.removeUntil { it.timeStamp + 1.minutes < now }
                if (bytesReadLog.size > 1) {
                    val timeDiff = now - bytesReadLog.first.timeStamp
                    val bytesDiff = totalReach - bytesReadLog.first.bytesRead
                    val timeRemaining = if (bytesDiff > 0)
                        formatTimeMinimal((totalSize - totalReach) * timeDiff / bytesDiff)
                    else ""
                    if (timeRemaining != "") status += " | $timeRemaining"
                }
            } else bytesReadLog.clear()

            ongoingBuilder.setProgress(100, progress.roundToInt(), progress > 99.99)
                .setContentText(status)
                .build().also {
                    if (progress > 99.99) {
                        if (!indefiniteProgressPosted) {
                            indefiniteProgressPosted = true
                            notifier.notify(ONGOING_NOTIFICATION_ID, it)
                        }
                    } else {
                        indefiniteProgressPosted = false
                        notifier.notify(ONGOING_NOTIFICATION_ID, it)
                    }
                }
        }
    }

    private var lastLoudNotificationTime = 0L
    private val completedTasks = mutableSetOf<TransferWrapper>()
    private fun showCompletedNotification(task: TransferWrapper) {
        if (task.hasFailed || completedTasks.any { it.hasFailed })
            completedBuilder.setSmallIcon(R.drawable.cross)
        else completedBuilder.setSmallIcon(R.drawable.done2)
        completedTasks.add(task)

        // if a loud notification is shown once, silence notifications for next 5 seconds
        val showSilently = now() - lastLoudNotificationTime < 5000
        if (Pref.disableNotificationSound || showSilently) {
            completedBuilder.setDefaults(NotificationCompat.DEFAULT_LIGHTS)
        } else {
            lastLoudNotificationTime = now()
            completedBuilder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        val len = completedTasks.size
        if (len == 1) {
            completedBuilder.setContentTitle(task.fileName.shorten(30))
                .setContentText(
                    if (task.hasSucceeded) "Downloaded ${task.fileName.shorten(20)}"
                    else "${task.fileName.shorten(20)} failed"
                ).build().also { notifier.notify(COMPLETED_NOTIFICATION_ID, it) }
        } else {
            val failed = completedTasks.count { it.hasFailed }
            completedBuilder.setContentTitle("Completed $len files")
                .setContentText(
                    if (failed == 0) {
                        val files = if (len == 2) "file" else "files"
                        "Downloaded ${task.fileName.shorten(15)} and ${len - 1} other $files"
                    } else "Successful: ${len - failed} | Failed: $failed"
                )
                .build().also { notifier.notify(COMPLETED_NOTIFICATION_ID, it) }
        }
    }

    fun cancelFailedNotification(task: TransferWrapper) {
        if (completedTasks.remove(task)) {
            if (completedTasks.size == 0)
                notifier.cancel(COMPLETED_NOTIFICATION_ID)
            else showCompletedNotification(completedTasks.first())
        }
    }

    fun addOrUpdate(task: TransferWrapper) {
        if (task in tasks) {
            tasks.remove(task) // the outdated item is removed (Note: task.equals function checks only for task.id)
            tasks.add(task) // fresh item is inserted
        } else if (task.isOngoing) tasks.add(task)
    }

    private fun getPendingIntent(reqCode: Int, pauseAll: Boolean = false): PendingIntent {
        val intent = Intent(service, MainActivity::class.java)
        if (pauseAll) intent.putExtra(C.misc.pauseAll, true)
        return PendingIntent.getActivity(
            service,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}