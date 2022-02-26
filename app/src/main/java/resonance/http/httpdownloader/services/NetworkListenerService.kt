package resonance.http.httpdownloader.services

import android.app.job.JobParameters
import android.app.job.JobService
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.helpers.onConnectivityRestored

class NetworkListenerService : JobService() {
    override fun onStopJob(params: JobParameters?): Boolean {
        log("NetworkListenerService", "onStopJob")
        return true
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        log("NetworkListenerService", "onStartJob")
        onConnectivityRestored { jobFinished(params, true) }
        return true
    }
}