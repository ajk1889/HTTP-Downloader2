package resonance.http.httpdownloader.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.TransferServiceConnection

class GlobalServiceReceiver(ctx: Context) : BroadcastReceiver() {
    private val broadCastHelper = TransferServiceConnection(ctx)
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        log("GlobalServiceReceiver", "onReceive: re-broadcasting")
        if (intent.action == C.filter.runScheduledTasks) {
            intent.action = C.filter.REQUEST
            intent.putExtra(C.misc.request, C.req.startScheduled)
        } else if (intent.action == C.filter.globalStartDownload) {
            intent.action = C.filter.START_DOWNLOAD
        }
        broadCastHelper.request(intent)
    }
}