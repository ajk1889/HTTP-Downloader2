package resonance.http.httpdownloader.helpers

import org.json.JSONArray
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass.Companion.pref
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.implementations.TransferWrapper

class Pref {
    enum class key {
        appendConflictingFiles, disableNotificationSound,
        autoSortFileJoiner, historyLimit, browserHome, maxParallelDownloads, isAdvancedMode1stTime,
        isDownloadItemEditFirst, lastAdvancedData, isFileModeFirstTime, lastAdvancedDownloadData,
        show4GbWarningAgain, isFAT32Probable, lastBrowserDownloadData, downloadLocation,
        lastDownloadId, useInternal, blockBrowserPopup, preferDesktopMode,
        useNonPersonalizedAds, streamModeDialogShown, streamRefreshDelay, promptDownloadName,
        reviewRequestCounter, appVersion, isMegaFirstTime, speedLimit,
        userAgentName, customUserAgent, scheduleDownloadIntroShown, retryList, autoRetryDownloads,
        itemsToBackup, showRemainingTimeInNotif, ignoreSslErrors, forceSingleTabMode;

        fun exists() = pref.contains(this.name)
        fun remove() = pref.edit().remove(this.name).apply()
    }

    companion object {
        var forceSingleTabMode: Boolean
            get() = pref.getBoolean(key.forceSingleTabMode.name, false)
            set(value) = pref.edit().putBoolean(key.forceSingleTabMode.name, value).apply()
        var showRemainingTimeInNotification: Boolean
            get() = pref.getBoolean(key.showRemainingTimeInNotif.name, true)
            set(value) = pref.edit().putBoolean(key.showRemainingTimeInNotif.name, value).apply()
        var itemsToBackup: BooleanArray
            get() = JSONArray(pref.getString(key.itemsToBackup.name, "[true, true, false]")).run {
                return BooleanArray(length()) { getBoolean(it) }
            }
            set(value) =
                pref.edit().putString(key.itemsToBackup.name, JSONArray(value).toString()).apply()
        var retryList: LongList
            get() = LongList(JSONArray(pref.getString(key.retryList.name, "[]"))) { retryList = it }
            set(value) = pref.edit().putString(key.retryList.name, value.toString()).apply()
        var userAgentName: String
            get() = pref.getString(key.userAgentName.name, "Default")!!
            set(value) = pref.edit().putString(key.userAgentName.name, value).apply()
        var customUserAgent: String?
            get() = pref.getString(key.customUserAgent.name, null)
            set(value) = pref.edit().putString(key.customUserAgent.name, value).apply()
        var speedLimit: Int
            get() = pref.getInt(key.speedLimit.name, Int.MAX_VALUE)
            set(value) = pref.edit().putInt(key.speedLimit.name, value).apply()
        var reviewRequestCounter: Int
            get() = pref.getInt(key.reviewRequestCounter.name, 5)
            set(value) = pref.edit().putInt(key.reviewRequestCounter.name, value).apply()
        var appVersion: Int
            get() = pref.getInt(key.appVersion.name, 0)
            set(value) = pref.edit().putInt(key.appVersion.name, value).apply()
        var streamRefreshDelay: Long
            get() = pref.getLong(key.streamRefreshDelay.name, 2000)
            set(value) {
                pref.edit().putLong(key.streamRefreshDelay.name, value).apply()
                log("streamRefreshDelay", value)
            }
        var promptDownloadName: Boolean
            get() = pref.getBoolean(key.promptDownloadName.name, true)
            set(value) {
                pref.edit().putBoolean(key.promptDownloadName.name, value).apply()
                log("promptDownloadName", value)
            }
        var streamModeDialogShown: Boolean
            get() = pref.getBoolean(key.streamModeDialogShown.name, false)
            set(value) {
                pref.edit().putBoolean(key.streamModeDialogShown.name, value).apply()
                log("streamModeDialogShown", value)
            }
        var scheduleDownloadIntroShown: Boolean
            get() = pref.getBoolean(key.scheduleDownloadIntroShown.name, false)
            set(value) = pref.edit().putBoolean(key.scheduleDownloadIntroShown.name, value).apply()
        var autoRetryDownloads: Boolean
            get() = pref.getBoolean(key.autoRetryDownloads.name, false)
            set(value) = pref.edit().putBoolean(key.autoRetryDownloads.name, value).apply()
        var useNonPersonalizedAds: String
            get() = pref.getString(key.useNonPersonalizedAds.name, "1") ?: "1"
            set(value) = pref.edit().putString(key.useNonPersonalizedAds.name, value).apply()
        var blockBrowserPopup: Boolean
            get() = pref.getBoolean(key.blockBrowserPopup.name, true)
            set(value) {
                pref.edit().putBoolean(key.blockBrowserPopup.name, value).apply()
                log("blockBrowserPopup", value)
            }
        var preferDesktopMode: Boolean
            get() = pref.getBoolean(key.preferDesktopMode.name, false)
            set(value) {
                if (value != preferDesktopMode) {
                    pref.edit().putBoolean(key.preferDesktopMode.name, value).apply()
                    log("preferDesktopMode", value)
                }
            }
        var ignoreSslErrors: Boolean
            get() = pref.getBoolean(key.ignoreSslErrors.name, false)
            set(value) {
                Browser.ignoreSslErrorsForThisSession = value
                pref.edit().putBoolean(key.ignoreSslErrors.name, value).apply()
            }
        var browserHome: String
            get() = pref.getString(key.browserHome.name, C.HOME_PAGE)!!
            set(value) = pref.edit().putString(key.browserHome.name, value).apply()
        var maxParallelDownloads: Int
            get() = pref.getInt(key.maxParallelDownloads.name, 10)
            set(value) = pref.edit().putInt(key.maxParallelDownloads.name, value).apply()
        var historyLimit: Int
            get() = pref.getInt(key.historyLimit.name, 500)
            set(value) = pref.edit().putInt(key.historyLimit.name, value).apply()
        var disableNotificationSound: Boolean
            get() = pref.getBoolean(key.disableNotificationSound.name, false)
            set(value) {
                pref.edit().putBoolean(key.disableNotificationSound.name, value).apply()
                log("disableNotificationSound", value)
            }
        var appendConflictingFiles: Boolean
            get() = pref.getBoolean(key.appendConflictingFiles.name, false)
            set(value) {
                pref.edit().putBoolean(key.appendConflictingFiles.name, value).apply()
                log("appendConflictingFiles", value)
            }
        var isAdvancedMode1stTime: Boolean
            get() = pref.getBoolean(key.isAdvancedMode1stTime.name, true)
            set(value) = pref.edit().putBoolean(key.isAdvancedMode1stTime.name, value).apply()
        var autoSortFileJoiner: Boolean
            get() = pref.getBoolean(key.autoSortFileJoiner.name, true)
            set(value) {
                pref.edit().putBoolean(key.autoSortFileJoiner.name, value).apply()
                log("autoSortFileJoiner", value)
            }
        var isDownloadItemEditFirst: Boolean
            get() = pref.getBoolean(key.isDownloadItemEditFirst.name, true)
            set(value) = pref.edit().putBoolean(key.isDownloadItemEditFirst.name, value).apply()
        var lastAdvancedData: JSONObject?
            get() = if (key.lastAdvancedData.name !in pref) null
            else JSONObject(pref.getString(key.lastAdvancedData.name, ""))
            set(value) = pref.edit().putString(
                key.lastAdvancedData.name,
                value.str
            ).apply()
        var lastAdvancedDownloadData: JSONObject?
            get() = if (key.lastAdvancedDownloadData.name !in pref) null
            else JSONObject(pref.getString(key.lastAdvancedDownloadData.name, ""))
            set(value) {
                if (value == null) {
                    pref.edit().remove(key.lastAdvancedDownloadData.name).apply()
                } else pref.edit().putString(
                    key.lastAdvancedDownloadData.name,
                    value.str
                ).apply()
            }
        var isFileModeFirstTime: Boolean
            get() = pref.getBoolean(key.isFileModeFirstTime.name, true)
            set(value) = pref.edit().putBoolean(key.isFileModeFirstTime.name, value).apply()
        var isMegaFirstTime: Boolean
            get() = pref.getBoolean(key.isMegaFirstTime.name, true)
            set(value) = pref.edit().putBoolean(key.isMegaFirstTime.name, value).apply()
        var show4GbWarningAgain: Boolean
            get() = pref.getBoolean(key.show4GbWarningAgain.name, true)
            set(value) = pref.edit().putBoolean(key.show4GbWarningAgain.name, value).apply()
        val isFAT32Probable: Boolean
            get() {
                return if (pref.contains(key.isFAT32Probable.name)) {
                    pref.getBoolean(key.isFAT32Probable.name, false)
                } else {
                    val isProbable = isFAT32Probable()
                    pref.edit().putBoolean(key.isFAT32Probable.name, isProbable).apply()
                    isProbable
                }
            }
        var lastBrowserDownloadData: JSONObject?
            get() {
                return JSONObject(
                    pref.getString(key.lastBrowserDownloadData.name, null) ?: return null
                )
            }
            set(value) {
                if (value == null) {
                    pref.edit().remove(key.lastBrowserDownloadData.name).apply()
                } else pref.edit().putString(
                    key.lastBrowserDownloadData.name,
                    value.str
                ).apply()
            }
        //Used to identify whether downloadLocation is URI(false) or File(true).
        var useInternal: Boolean
            get() = pref.getBoolean(key.useInternal.name, true)
            set(value) = pref.edit().putBoolean(key.useInternal.name, value).apply()
        //May contain a URI string or a file path string. To identify the type, use @property useInternal
        var downloadLocation: String
            get() = pref.getString(
                key.downloadLocation.name,
                C.INTERNAL_DOWNLOAD_FOLDER.absolutePath
            )!!
            set(value) = pref.edit().putString(key.downloadLocation.name, value).apply()
        var lastDownloadId: Long
            get() = pref.getLong(key.lastDownloadId.name, 0)
            set(value) = pref.edit().putLong(key.lastDownloadId.name, value).apply()
    }

    class LongList(jsonArray: JSONArray, private val onUpdate: (LongList) -> Unit) {
        val items: MutableSet<Long> = mutableSetOf()

        init {
            for (i in 0 until jsonArray.length())
                items.add(jsonArray.getLong(i))
        }

        fun add(taskId: Long): LongList {
            if (items.add(taskId))
                onUpdate(this)
            return this
        }

        fun remove(taskId: Long): LongList {
            if (items.remove(taskId))
                onUpdate(this)
            return this
        }

        fun isEmpty(): Boolean = items.isEmpty()

        fun add(task: TransferWrapper) = add(task.id)
        fun remove(task: TransferWrapper) = remove(task.id)
        fun clear(): LongList {
            items.clear()
            onUpdate(this)
            return this
        }

        operator fun iterator() = items.iterator()
        override fun toString(): String = JSONArray(items).toString()
    }
}