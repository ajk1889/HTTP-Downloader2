package resonance.http.httpdownloader

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import resonance.http.httpdownloader.broadcastReceivers.GlobalServiceReceiver
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.db.LogDB
import resonance.http.httpdownloader.helpers.db.LogDbConn
import resonance.http.httpdownloader.helpers.isNetworkLive
import resonance.http.httpdownloader.helpers.onConnectivityRestored
import resonance.http.httpdownloader.services.NetworkListenerService
import java.io.File

class ApplicationClass : Application() {
    companion object {
        lateinit var logDB: LogDbConn
        lateinit var pref: SharedPreferences
        var lastSentIntentId = 0L
        var lastReceivedIntentId = 0L
    }

    val ioScope = CoroutineScope(Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        pref = PreferenceManager.getDefaultSharedPreferences(this)
        logDB = Room.databaseBuilder(this, LogDB::class.java, "logs").build().conn()
        ioScope.launch {
            File(Environment.getExternalStorageDirectory(), "HTTP-Downloads").mkdirs()
        }
        registerReceiver(GlobalServiceReceiver(this), IntentFilter().apply {
            addAction(C.filter.runScheduledTasks)
            addAction(C.filter.globalStartDownload)
        })
        scheduleNetworkListener()
    }

    private fun scheduleNetworkListener() {
        with(getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler) {
            schedule(
                JobInfo.Builder(
                    7342,
                    ComponentName(this@ApplicationClass, NetworkListenerService::class.java)
                ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onConnectivityRestored()
                }
            )
        } else {
            val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (isNetworkLive()) onConnectivityRestored()
                }
            }, intentFilter)
        }
    }
}