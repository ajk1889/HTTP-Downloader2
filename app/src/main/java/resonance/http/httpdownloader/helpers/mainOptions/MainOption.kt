package resonance.http.httpdownloader.helpers.mainOptions

import android.os.Handler
import android.view.View
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.SimpleActivity
import resonance.http.httpdownloader.core.DownloadObject
import resonance.http.httpdownloader.core.orElse
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.C
import resonance.http.httpdownloader.helpers.Option
import resonance.http.httpdownloader.helpers.getUniqueId

abstract class MainOption(internal val activity: SimpleActivity) {
    private val handler = Handler()
    var response: DownloadObject.ResponseData? = null

    internal abstract fun make(): Option

    fun Option.click() {
        activity.questionFactory.isForwardButtonClick = true
        view.findViewById<View>(R.id.text).callOnClick()
        activity.questionFactory.isForwardButtonClick = false
    }

    private var onGoingRequestExist = false
    private var prevBytesToRead: Int = 0
    private val onResponseListeners = mutableListOf<(DownloadObject.ResponseData) -> Unit>()
    private val onErrorResponseListeners = mutableListOf<(Exception?) -> Unit>()

    @Synchronized
    fun runWithResponse(
        data: JSONObject,
        bytesToRead: Int = 0,
        onSuccess: (response: DownloadObject.ResponseData) -> Unit,
        onError: (e: Exception?) -> Unit
    ) {
        if (response == null) {
            if (onGoingRequestExist) {
                if (prevBytesToRead == bytesToRead) {
                    onResponseListeners.add(onSuccess)
                    onErrorResponseListeners.add(onError)
                } else {
                    prevBytesToRead = bytesToRead
                    fetchData(data, bytesToRead, onSuccess, onError)
                }
            } else fetchData(data, bytesToRead, onSuccess, onError)
        } else {
            val res = response ?: return runWithResponse(data, bytesToRead, onSuccess, onError)

            // in case of configuration changed
            if (data.optLong(C.dt.offset, 0) != res.offset
                || (data.optLong(C.dt.limit, -1) orElse res.total) != res.limit
                || data.optString(C.dt.url, "") != res.conn.url.str
                || !res.matchesHeaders(data.optString(C.dt.headers, ""))
            ) {
                response = null
                return runWithResponse(data, bytesToRead, onSuccess, onError)
            }

            if (bytesToRead == 0) {
                onSuccess(res)
                if (prevBytesToRead == bytesToRead) {
                    for (onResponse in onResponseListeners)
                        onResponse(res)
                    onResponseListeners.clear()
                }
            } else {
                if (res.bytes.isNotEmpty() && res.bytes.size <= bytesToRead) {
                    onSuccess(res)
                    if (prevBytesToRead == bytesToRead) {
                        for (onResponse in onResponseListeners)
                            onResponse(res)
                        onResponseListeners.clear()
                    }
                } else {
                    response = null
                    runWithResponse(data, bytesToRead, onSuccess, onError)
                }
            }
        }
    }

    private fun fetchData(
        data: JSONObject,
        bytesToRead: Int = 0,
        onSuccess: (response: DownloadObject.ResponseData) -> Unit,
        onError: (e: Exception?) -> Unit
    ) {
        if (!data.has(C.dt.id)) data.put(C.dt.id, getUniqueId())
        val obj = DownloadObject(data)
        obj.onFetchFailed = { _, e ->
            onGoingRequestExist = false
            handler.post {
                onError(e)
                if (prevBytesToRead == bytesToRead) {
                    for (onErrorResponse in onErrorResponseListeners)
                        onErrorResponse(e)
                    onErrorResponseListeners.clear()
                }
            }
        }
        obj.onFetchSuccess = { _, response ->
            this.response = response
            onGoingRequestExist = false
            handler.post {
                if (response != null) {
                    onSuccess(response)
                    if (prevBytesToRead == bytesToRead) {
                        for (onResponse in onResponseListeners)
                            onResponse(response)
                        onResponseListeners.clear()
                    }
                } else {
                    onError(null)
                    if (prevBytesToRead == bytesToRead) {
                        for (onErrorResponse in onErrorResponseListeners)
                            onErrorResponse(null)
                        onErrorResponseListeners.clear()
                    }
                }
            }
        }
        onGoingRequestExist = true
        if (bytesToRead == 0) obj.startFetchingDetails()
        else obj.startFetchingDetails(bytesToRead)
    }
}