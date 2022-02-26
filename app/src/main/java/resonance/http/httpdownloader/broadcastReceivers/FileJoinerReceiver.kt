package resonance.http.httpdownloader.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_file_joiner.*
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.activities.FileJoiner
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.id
import resonance.http.httpdownloader.helpers.isDuplicate
import resonance.http.httpdownloader.services.JoinerService

class FileJoinerReceiver(private val activity: FileJoiner) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if ((intent ?: return).isDuplicate()) return
        ApplicationClass.lastReceivedIntentId = intent.id
        JoinerService.lastBroadcast = null

        if (intent.action == C.filter.JOINER_PROGRESS) {
            activity.disableAll()
            if (!activity.isManuallyToggled)
                activity.toggle(activity.title3)
            activity.updateProgress(intent)
        } else if (intent.action == C.filter.JOINING_FAILED) {
            activity.enableAll()
            activity.failedOnce = true
            val reason = intent.getStringExtra("exception")
            activity.showSnackBar(
                msg = "File joining was failed. Reason: $reason",
                duration = Snackbar.LENGTH_INDEFINITE
            )
        }
    }
}