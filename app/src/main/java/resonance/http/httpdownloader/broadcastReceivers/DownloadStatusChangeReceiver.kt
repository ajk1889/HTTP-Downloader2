package resonance.http.httpdownloader.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.findItemById
import resonance.http.httpdownloader.helpers.id
import resonance.http.httpdownloader.helpers.isDuplicate

class DownloadStatusChangeReceiver(private val activity: MainActivity) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if ((intent ?: return).isDuplicate()) return
        ApplicationClass.lastReceivedIntentId = intent.id

        if (intent.action == C.filter.DONE) {
            log(
                "DownloadStatusChangeReceiver",
                "onReceive: received DONE",
                activity.doneListeners.size,
                "extras=" + intent.extras
            )
            activity.doneListeners.values.toTypedArray().forEach { it.invoke(intent) }
            return
        }

        intent.getStringExtra("task")?.also { task ->
            val json = JSONObject(task)
            val id = json.getLong(C.dt.id)
            log("DownloadStatusChangeReceiver", "onReceive: extras=" + intent.extras)
            with(activity.adapter.findItemById(id)) {
                copyContentsFrom(json)
                if (json.optString(C.dt.statusIcon) == C.ico.warning && json.has(C.dt.exceptionCause))
                    activity.adapter.showWarningReasonAndSolution(
                        json.optString(C.dt.exceptionCause),
                        this
                    )
            }
            activity.adapter.notifyDataSetChanged()
        }
    }
}