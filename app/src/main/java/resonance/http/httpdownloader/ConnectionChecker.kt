package resonance.http.httpdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.core.seconds
import resonance.http.httpdownloader.helpers.*
import kotlin.concurrent.thread

class ConnectionChecker : Service() {
    companion object {
        const val RETRY_CHANNEL = "connectivityRetryChannel"
        const val NOTIFICATION_ID = 2387
    }

    lateinit var notificationManager: NotificationManager
    var isRunning = true
    override fun onBind(intent: Intent): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoing = NotificationChannel(
                RETRY_CHANNEL,
                "Checking connection",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "For checking network connection to retry downloads"
                setSound(null, null)
            }
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(ongoing)
        }
        val intent = Intent(this, ConnectionChecker::class.java)
        intent.putExtra(C.misc.request, C.req.cancelAutoRetry)
        val pendingIntent =
            PendingIntent.getService(this, 2343, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, RETRY_CHANNEL)
            .setContentTitle("Checking connection...")
            .setSmallIcon(R.drawable.cloud_download)
            .setContentText("Downloads will be resumed when connectivity is restored")
            .addAction(R.drawable.cross, "Cancel", pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        startConnectionCheckingThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(C.misc.request) == C.req.cancelAutoRetry) {
            Pref.autoRetryDownloads = false
            stop("cancel request received")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        log("ConnectionChecker", "OnDestroy")
        isRunning = false
    }

    private fun startConnectionCheckingThread() = thread {
        var lastCheckTime = 0L
        while (isRunning) {
            Thread.sleep(10)
            val now = now()
            if (now - lastCheckTime > 20.seconds) {
                lastCheckTime = now
                when {
                    !Pref.autoRetryDownloads -> stop("Auto-retry disabled")
                    Pref.retryList.isEmpty() -> stop("retryList is empty")
                    !isNetworkLive() -> stop("All connections disabled")
                    pingGoogle() -> onConnectivityRestored { stop("Connectivity restored") }
                }
            }
        }
    }

    fun stop(reason: String) {
        log("ConnectionChecker", "Stopping service: $reason")
        isRunning = false
        stopSelf()
    }
}
