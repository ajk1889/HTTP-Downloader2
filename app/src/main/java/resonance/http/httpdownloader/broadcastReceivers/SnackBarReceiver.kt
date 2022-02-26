package resonance.http.httpdownloader.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.helpers.id
import resonance.http.httpdownloader.helpers.isDuplicate

class SnackBarReceiver(private val activity: MainActivity) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if ((intent ?: return).isDuplicate()) return
        val msg = intent.getStringExtra("message") ?: return
        ApplicationClass.lastReceivedIntentId = intent.id
        activity.showSnackBar(msg)
    }
}