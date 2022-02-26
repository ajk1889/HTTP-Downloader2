package resonance.http.httpdownloader.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.id
import resonance.http.httpdownloader.helpers.isDuplicate

class ProgressUpdateReceiver(private val activity: MainActivity) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if ((intent ?: return).isDuplicate()) return
        ApplicationClass.lastReceivedIntentId = intent.id

        val ids = intent.getLongArrayExtra(C.dt.id) ?: return
        val progress = intent.getIntArrayExtra(C.dt.progress)
        val downloaded = intent.getStringArrayExtra(C.dt.downloaded)
        val reach = intent.getStringArrayExtra(C.dt.reach)
        val speed = intent.getStringArrayExtra(C.dt.speed)
        val percent = intent.getStringArrayExtra(C.dt.percent)
        for (item in activity.adapter) {
            if (item == null || item.id !in ids) continue
            ids.indexOf(item.id).also {
                if (progress != null) item.progress = progress[it]
                if (downloaded != null) item.downloaded = downloaded[it]
                if (reach != null) item.reach = reach[it]
                if (speed != null) item.speed = speed[it]
                if (percent != null) item.percent = percent[it]
            }
        }
        activity.adapter.notifyDataSetChanged()
    }
}