package resonance.http.httpdownloader.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.services.TransferService

class TransferServiceConnection(private val context: Context) {
    @Synchronized
    fun request(intent: Intent) {
        intent.id = ++ApplicationClass.lastSentIntentId
        intent.setClass(context, TransferService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    companion object {
        val thisFeature: String get() = "dGhpcyBmZWF0dXJlLg=="
    }
}