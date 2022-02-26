package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.helpers.db.HistoryDB
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class SettingsActivity : ParentActivity() {
    object BackupOptions {
        const val APP_SETTINGS = 0
        const val DOWNLOAD_DATA = 1
        const val BROWSER_DATA = 2
        val dbFiles = listOf(
            "bookmarks", "bookmarks-wal", "bookmarks-shm",
            "browserHistory", "browserHistory-wal", "browserHistory-shm",
            "savedPages", "savedPages-wal", "savedPages-shm"
        )
    }

    companion object {
        const val FOLDER_CHOOSE = 1287
        const val BACKUP_CHOOSE = 1288
        const val RESTORE_CHOOSE = 1289
        fun start(activity: Activity, from: String, extras: Intent? = null) {
            val intent = Intent(activity, SettingsActivity::class.java)
            extras?.also { intent.putExtras(it) }
            intent.putExtra(C.misc.from, from)
            activity.startActivity(intent)
        }
    }

    var from = "MainActivity"
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val timesList = listOf(1000L, 2000L, 3000L, 5000L, 8000L, 12000L)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        userAgentSpinner.adapter = ArrayAdapter<String>(this, R.layout.spinner_item)
            .apply { addAll(userAgentNames) }
        updateUserAgentSpinner()

        //intentionally kept above setEventListeners to avoid snackBar
        historyLimit.setText(Pref.historyLimit.str)
        maxParallelDownloads.setText(Pref.maxParallelDownloads.str)
        autoSort.isChecked = Pref.autoSortFileJoiner
        disableSound.isChecked = Pref.disableNotificationSound
        appendFiles.isChecked = Pref.appendConflictingFiles
        blockPopup.isChecked = Pref.blockBrowserPopup
        toggleDownloadPrompt.isChecked = Pref.promptDownloadName
        autoRetry.isChecked = Pref.autoRetryDownloads
        showRemainingTime.isChecked = Pref.showRemainingTimeInNotification
        ignoreSslErrors.isChecked = Pref.ignoreSslErrors || Browser.ignoreSslErrorsForThisSession
        preferDesktopMode.isChecked = Pref.preferDesktopMode && true
        updateSpeedLimitText(Pref.speedLimit)
        from = intent?.getStringExtra(C.misc.from) ?: "MainActivity"

        setEventListeners()

        if (!Pref.useInternal) setFolderName(Pref.downloadLocation)
        homePage.setText(Pref.browserHome)

        refreshSpinnerInfo.setOnClickListener { refreshIntervalInfo() }
        alwaysDesktopMode.setOnClickListener { preferDesktopMode() }
        ignoreSslErrorsParent.setOnClickListener { ignoreSslErrors() }
        up.setOnClickListener { up() }
        down.setOnClickListener { down() }
        limitSpeed.setOnClickListener { limitDownloadSpeed() }
        dataBackupLayout.setOnClickListener { showBackupRestoreDialog() }
    }

    override fun onBackPressed() {
        if (snackBar != null) {
            dismissSnackBar()
            return
        }
        // browser & mainActivity are singleInstance (see Manifest.xml) & will always be resumed
        val intent: Intent = when (from) {
            MainActivity::class.java.name -> Intent(this, MainActivity::class.java)
            Browser::class.java.name -> Intent(this, Browser::class.java)
            IntroActivity::class.java.name -> Intent(this, IntroActivity::class.java)
            SimpleActivity::class.java.name -> Intent(this, SimpleActivity::class.java)
            else -> {
                super.onBackPressed(); return
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        super.onBackPressed()
    }
    override fun onResume() {
        super.onResume()
        maxParallelDownloads.setText(Pref.maxParallelDownloads.str)

        if (Pref.streamModeDialogShown) {
            val adapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line)
            adapter.addAll("1 sec", "2 sec", "3 sec", "5 sec", "8 sec", "12 sec")
            refreshSpinner.adapter = adapter
            refreshSpinner.setSelection(timesList.indexOf(Pref.streamRefreshDelay))
        } else {
            downloadRefreshLayout.setGone()
            downloadRefreshLine.setGone()
        }

        val childToScrollTo = intent?.getIntExtra(C.misc.childToScrollTo, 0) ?: 0
        scroller.post { scroller.smoothScrollTo(0, childToScrollTo) }
    }

    private fun updateUserAgentSpinner() = try {
        userAgentSpinner.setSelection(userAgentNames.indexOf(Pref.userAgentName))
    } catch (e: Exception) {
        userAgentSpinner.setSelection(0)
        Pref.userAgentName = userAgentNames[0]
        Pref.customUserAgent = null
    }

    private fun setEventListeners() {
        fun setUserAgent(name: String, value: String?) {
            Pref.userAgentName = name
            Pref.customUserAgent = value
            showSnackBar("Browser environment will be $name for newly opened tabs")
        }
        showRemainingTime.setOnCheckedChangeListener { _, isChecked ->
            Pref.showRemainingTimeInNotification = isChecked
        }
        autoRetry.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) showSnackBar("Failed downloads will automatically be retried when network connection is restored")
            else showSnackBar("Downloads won't be automatically retried anymore")
            Pref.autoRetryDownloads = isChecked
        }
        userAgentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var isFirstTime = true
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            @SuppressLint("InflateParams")
            override fun onItemSelected(a: AdapterView<*>?, b: View?, index: Int, c: Long) {
                if (isFirstTime) {
                    isFirstTime = false
                    return
                }
                val name = userAgentNames[index]
                if (name !in userAgents) {
                    val view = layoutInflater.inflate(R.layout.dialog_edit_text_single_line, null)
                    val editText = view.findViewById<EditText>(R.id.editText)
                    editText.setHint(R.string.sample_user_agent)
                    editText.setText(Pref.customUserAgent)
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("User Agent: ")
                        .setView(view)
                        .setPositiveButton("Apply") { _, _ ->
                            val text = editText.text.toString()
                            editText.selectAll()
                            if (text.isBlank()) {
                                showSnackBar("User Agent cannot be blank. Changes discarded")
                                updateUserAgentSpinner()
                            } else setUserAgent(name, text)
                        }
                        .setNegativeButton("Cancel") { _, _ -> updateUserAgentSpinner() }
                        .setOnCancelListener { updateUserAgentSpinner() }
                        .setOnDismissListener { updateUserAgentSpinner() }
                        .show()
                } else setUserAgent(name, userAgents[name])
            }
        }

        refreshSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }

            override fun onItemSelected(a: AdapterView<*>?, b: View?, position: Int, c: Long) {
                Pref.streamRefreshDelay = timesList[position]
            }
        }
        maxParallelDownloads.setOnTextChangedListener {
            val count = { it.str.toInt() } otherwise 1
            if (count == 0) {
                showSnackBar("Parallel downloads' count cannot be zero")
                maxParallelDownloads.setText("1")
                maxParallelDownloads.setSelection(1)
            } else Pref.maxParallelDownloads = count
        }

        homePage.setOnTextChangedListener {
            Pref.browserHome = it.str
        }

        historyLimit.setOnTextChangedListener {
            val count = { it.str.toInt() } otherwise 100
            when {
                count == 0 ->
                    showSnackBar("Your browser won't store history when limit is zero")
                count > 5000 -> {
                    historyLimit.setText("5000")
                    historyLimit.setSelection(4)
                    return@setOnTextChangedListener
                }
                count > 1000 ->
                    showSnackBar("Setting this limit too much causes History to hang in low end devices")
            }
            Pref.historyLimit = count
        }

        blockPopup.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) showSnackBar("Websites won't be allowed to launch a new tab")
            else showSnackBar("Websites are allowed to launch new tabs")
            Pref.blockBrowserPopup = isChecked
        }
        toggleDownloadPrompt.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) showSnackBar("Download location will always be asked when starting a download from browser")
            else showSnackBar("Files will directly start download to default folder")
            Pref.promptDownloadName = isChecked
        }
        preferDesktopMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) showSnackBar("All websites will be loaded in desktop mode")
            else showSnackBar("Websites will be launched in mobile friendly mode")
            Pref.preferDesktopMode = isChecked
        }
        ignoreSslErrors.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Ignore SSL errors?")
                    .setMessage("Any SSL security errors will be ignored while loading page. This might make you vulnerable to attacks")
                    .setPositiveButton("Ignore anyway (unsafe)") { _, _ ->
                        Pref.ignoreSslErrors = true
                    }
                    .setNegativeButton("Remain safe") { _, _ ->
                        Pref.ignoreSslErrors = false
                        ignoreSslErrors.isChecked = false
                    }
                    .setNeutralButton("Ignore for this session") { _, _ ->
                        Browser.ignoreSslErrorsForThisSession = true
                    }
                    .setCancelable(false)
                    .show()
            } else Pref.ignoreSslErrors = false
        }
        autoSort.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                showSnackBar(
                    "Files won't be sorted before adding to file joiner.<br/>" +
                            "You may have to manually re-order items before joining"
                )
            } else showSnackBar("HTTP-Downloader will sort files (whose names are of default format) before adding")
            Pref.autoSortFileJoiner = isChecked
        }
        disableSound.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked)
                showSnackBar("You won't be notified when your download fails or succeeds")
            Pref.disableNotificationSound = isChecked
        }
        appendFiles.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showSnackBar(
                    "When a download is started, and a file with same name exists, " +
                            "the download will be requested from end of existing file & will be appended to it"
                )
            } else {
                showSnackBar(
                    "When a download is started, and a file with same name exists, " +
                            "the download will automatically be renamed."
                )
            }
            Pref.appendConflictingFiles = isChecked
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            data?.data?.also {
                when (requestCode) {
                    FOLDER_CHOOSE -> {
                        contentResolver.takePersistableUriPermission(it, data.getRWFlags())
                        setFolderName(it.str)
                        Pref.downloadLocation = it.str
                        Pref.useInternal = false
                    }
                    BACKUP_CHOOSE -> backupDataTo(contentResolver.openOutputStream(it))
                    RESTORE_CHOOSE -> restoreDataFrom(contentResolver.openInputStream(it))
                }
            } ?: showSnackBar("Invalid output destination", duration = Snackbar.LENGTH_LONG)
        } else showSnackBar("No output destination selected", duration = Snackbar.LENGTH_LONG)
    }

    fun changeDownloadFolder(view: View) =
        launchFilePicker(Intent.ACTION_OPEN_DOCUMENT_TREE, FOLDER_CHOOSE)

    private fun down() {
        val text = maxParallelDownloads.text.str
        val count = { text.toInt() } otherwise 1
        val countText = (count - 1).str
        maxParallelDownloads.setText(countText)
        maxParallelDownloads.setSelection(countText.length)
    }

    private fun up() {
        val text = maxParallelDownloads.text.str
        val count = { text.toInt() } otherwise 1
        if (count == 99) {
            showSnackBar("Maximum parallel downloads cannot be more than 99")
            return
        }
        val countText = (count + 1).str
        maxParallelDownloads.setText(countText)
        maxParallelDownloads.setSelection(countText.length)
    }

    fun allowAutoSort(view: View) {
        autoSort.isChecked = !autoSort.isChecked
    }

    fun disableNotifSound(view: View) {
        disableSound.isChecked = !disableSound.isChecked
    }

    fun appendFile(view: View) {
        appendFiles.isChecked = !appendFiles.isChecked
    }

    fun reset(view: View) {
        AlertDialog.Builder(this)
            .setTitle("Reset settings")
            .setMessage("Are you sure you want to reset settings to default?")
            .setPositiveButton("Yes") { d, _ ->
                log("SettingsActivity", "reset")
                d.dismiss()
                Pref.useInternal = true
                Pref.downloadLocation = C.INTERNAL_DOWNLOAD_FOLDER.absolutePath
                Pref.maxParallelDownloads = 10
                Pref.browserHome = C.HOME_PAGE
                Pref.historyLimit = 500
                Pref.autoSortFileJoiner = true
                Pref.disableNotificationSound = false
                Pref.appendConflictingFiles = false
                finish()
                start(this, from)
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    fun knowMore(view: View) {
        log("SettingsActivity", "knowMore")
        Browser.start(
            context = this,
            from = Browser.Companion.FromOptions.SETTINGS,
            url = "https://resonance00x0.github.io/http-downloader/",
            request = C.misc.startDownload
        )
    }

    fun help(view: View) {
        log("SettingsActivity", "help")
        Browser.start(
            context = this,
            from = Browser.Companion.FromOptions.SETTINGS,
            url = "https://resonance00x0.github.io/http-downloader/faq",
            request = C.misc.startDownload
        )
    }

    fun reportBug(view: View) = showConfirmSendMailAlert {
        log("SettingsActivity", "reportBug")
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.type = "message/rfc822"
        intent.data = Uri.parse(
            "mailto:resonance00x0@gmail.com" +
                    "?subject=Bug%2FSuggestion%20in%20HTTP-Downloader"
        )
        silently {
            val externalFile = getDatabasePath("logs").copyTo(
                target = File(getExternalFilesDir(null), "logs"),
                overwrite = true
            )
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(externalFile))
        }

        try {
            startActivity(Intent.createChooser(intent, "Send email..."))
        } catch (e: ActivityNotFoundException) {
            showSnackBar("Please install an email app and try again")
        }
    }

    private fun setFolderName(uriStr: String) {
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            folderName.text = "<font color=red>Error</font>".asHtml()
            return
        }
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return
        if (folder.isFile || folder.name == null) return
        folderName.text = "${folder.name}/"
    }

    fun clearBrowsingData(view: View) {
        val selected = mutableListOf(0, 1)
        AlertDialog.Builder(this)
            .setTitle("Clear data")
            .setMultiChoiceItems(
                arrayOf("History", "Cache", "Cookies", "Site storage"),
                booleanArrayOf(true, true, false, false)
            ) { _, which, isChecked ->
                if (isChecked) selected.add(which)
                else selected.remove(which)
            }
            .setPositiveButton("Clear") { d, _ ->
                log("SettingsActivity", "clearBrowsingData: $selected")
                thread {
                    if (0 in selected) {
                        Room.databaseBuilder(
                            applicationContext,
                            HistoryDB::class.java, "browserHistory"
                        ).build().conn().clearAll()
                    }
                    if (1 in selected)
                        File(getDir("webview", Context.MODE_PRIVATE), "Cache").deleteRecursively()
                    if (3 in selected) WebStorage.getInstance().deleteAllData()
                    handler.post {
                        if (2 in selected) CookieManager.getInstance().apply {
                            removeAllCookies(null)
                            flush()
                        }
                        showSnackBar("Cleared all data")
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    fun blockBrowserPopup(view: View) {
        blockPopup.isChecked = !blockPopup.isChecked
        Pref.blockBrowserPopup = blockPopup.isChecked
    }

    private fun preferDesktopMode() {
        preferDesktopMode.isChecked = !preferDesktopMode.isChecked
    }

    private fun ignoreSslErrors() {
        ignoreSslErrors.isChecked = !ignoreSslErrors.isChecked
    }

    fun clearDownloads(view: View) {
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage(
                """This will clear all your downloads from list.<br>
                |<b>It is recommended to stop all ongoing downloads before you clear them</b><br>
                |<font color=red>Note: This action cannot be undone</font>""".trimMargin().asHtml()
            )
            .setPositiveButton("clear") { d, _ ->
                uiScope.launch {
                    configsFolder(this@SettingsActivity).listFiles()?.forEach {
                        withContext(Dispatchers.IO) { it.delete() }
                    }
                    showSnackBar("All downloads cleared")
                }
                d.dismiss()
            }
            .setNegativeButton("cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun refreshIntervalInfo() {
        Browser.start(
            context = this,
            from = Browser.Companion.FromOptions.SETTINGS,
            url = "https://resonance00x0.github.io/http-downloader/faq" +
                    "#what-is-continuous-reconnect-mode",
            request = C.misc.startDownload
        )
    }

    fun toggleDownloadPrompt(view: View) {
        toggleDownloadPrompt.isChecked = !toggleDownloadPrompt.isChecked
        Pref.promptDownloadName = toggleDownloadPrompt.isChecked
    }

    private fun limitDownloadSpeed() = showSpeedLimitDialog(Pref.speedLimit) {
        Pref.speedLimit = it
        Transfer.globalSpeedLimit = it
        updateSpeedLimitText(it)
    }

    private fun updateSpeedLimitText(it: Int) = if (it != Int.MAX_VALUE) {
        limitSpeedTxt.text =
            "Maximum download speed: ${formatSize(Pref.speedLimit.toLong(), 2)}/s"
        limitSpeedTxt.setTextColor(ContextCompat.getColor(this, R.color.colorAccent_light))
        limitSpeedIco.setImageResource(R.drawable.speed_limited)
    } else {
        limitSpeedTxt.setText(R.string.limit_download_speed)
        limitSpeedTxt.setTextColor(Color.BLACK)
        limitSpeedIco.setImageResource(R.drawable.speed)
    }

    fun autoRetry(view: View) {
        autoRetry.isChecked = !autoRetry.isChecked
    }

    private fun showBackupRestoreDialog() {
        fun showBackupDialog() {
            val selectedOptions = Pref.itemsToBackup
            AlertDialog.Builder(this)
                .setTitle("Items to backup")
                .setMultiChoiceItems(
                    arrayOf("App settings", "Download items", "Browser data"),
                    selectedOptions
                ) { _, index, isChecked ->
                    selectedOptions[index] = isChecked
                    Pref.itemsToBackup = selectedOptions
                }
                .setPositiveButton("Backup") { _, _ ->
                    launchFilePicker(Intent.ACTION_CREATE_DOCUMENT, BACKUP_CHOOSE)
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        }

        fun chooseRestoreFile() = launchFilePicker(Intent.ACTION_OPEN_DOCUMENT, RESTORE_CHOOSE)
        val msg =
            """This will let you backup/restore your download data and app settings to a portable zip file.<br/>
                    | You can restore this data from any device with <b>HTTP-Downloader 0.4.9 or later</b><br/><br/>
                    | <font color='red'>It is recommended to keep the backup file confidential as 
                    | it might contain sensitive user data</font>""".trimMargin().asHtml()
        AlertDialog.Builder(this)
            .setTitle("Backup/Restore")
            .setMessage(msg)
            .setPositiveButton("Backup") { _, _ -> showBackupDialog() }
            .setNegativeButton("Restore") { _, _ -> chooseRestoreFile() }
            .setNeutralButton("Cancel") { _, _ -> }
            .show()
    }

    private fun backupDataTo(op: OutputStream?) {
        if (op == null) return
        val ioScope = CoroutineScope(Dispatchers.IO)
        ioScope.start {
            if (backupSync(op))
                uiScope.launch { showSnackBar("Backup successful") }
            else uiScope.launch { showSnackBar("Failed to backup data") }
        }
    }

    private fun backupSync(op: OutputStream): Boolean {
        try {
            val itemsToBackup = Pref.itemsToBackup
            ZipOutputStream(op).use { zip ->
                if (itemsToBackup[BackupOptions.APP_SETTINGS]) {
                    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this).all
                    val settings = JSONObject()
                    for (key in Pref.key.values()) {
                        val value = sharedPref[key.name] ?: continue
                        settings.put(key.name, JSONObject().apply {
                            put("value", value)
                            put("type", value.javaClass.simpleName)
                        })
                    }
                    zip.putNextEntry(ZipEntry("preferences.json"))
                    zip.write(settings.toString().toByteArray())
                }
                if (itemsToBackup[BackupOptions.DOWNLOAD_DATA]) {
                    configsFolder(this).listFiles()?.forEach { file ->
                        zip.putNextEntry(ZipEntry("downloads/${file.name}"))
                        FileInputStream(file).use { it.copyTo(zip) }
                    }
                }
                if (itemsToBackup[BackupOptions.BROWSER_DATA]) {
                    File(filesDir.parentFile, "databases").listFiles()?.forEach { file ->
                        if (file.isFile && file.name in BackupOptions.dbFiles) {
                            zip.putNextEntry(ZipEntry("databases/${file.name}"))
                            FileInputStream(file).use { it.copyTo(zip) }
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            log("Backup data", e)
        }
        return false
    }

    private fun restoreDataFrom(ip: InputStream?) {
        if (ip == null) return
        val ioScope = CoroutineScope(Dispatchers.IO)
        ioScope.start {
            if (restoreSync(ip))
                uiScope.launch { showSnackBar("Data restored, reboot device to complete the process") }
            else uiScope.launch { showSnackBar("Restoring data failed, no changes were made") }
        }
    }

    private fun restoreSync(ip: InputStream, isInternalRestore: Boolean = false): Boolean {
        fun writeToPref(
            editor: SharedPreferences.Editor,
            key: String,
            value: String,
            type: String
        ) = when (type) {
            "Integer" -> editor.putInt(key, value.toInt())
            "Long" -> editor.putLong(key, value.toLong())
            "Boolean" -> editor.putBoolean(key, value.toBoolean())
            "String" -> editor.putString(key, value)
            "Float" -> editor.putFloat(key, value.toFloat())
            else -> throw RuntimeException("Type $type is not supported for restoring")
        }

        val currentDataBackup = File(filesDir, "data_backup.zip")
        try {
            if (!isInternalRestore)
                backupSync(FileOutputStream(currentDataBackup))
            ZipInputStream(ip).use { zip ->
                var entry = zip.nextEntry
                val dbFolder = File(filesDir.parentFile, "databases")
                val configsPath = configsFolder(this)
                while (entry != null) {
                    when {
                        entry.name == "preferences.json" -> {
                            if (entry.size > 100.KB) throw RuntimeException(
                                "Over-sized preferences.json (${formatSize(entry.size)} = ${entry.size} bytes)"
                            )
                            val rawData = String(zip.readBytes())
                            val data = JSONObject(rawData)
                            zip.closeEntry()
                            val pref = PreferenceManager.getDefaultSharedPreferences(this).edit()
                            for (key in data.keys()) data.getJSONObject(key).run {
                                writeToPref(pref, key, getString("value"), getString("type"))
                            }
                            pref.apply()
                        }
                        entry.name.startsWith("databases/") -> {
                            val fileName = entry.name.substring("databases/".length)
                            if (fileName in BackupOptions.dbFiles) {
                                FileOutputStream(File(dbFolder, fileName)).use {
                                    if (entry.size > 5.MB) throw RuntimeException(
                                        "Over-sized $fileName (${formatSize(entry.size)} = ${entry.size} bytes)"
                                    )
                                    zip.copyTo(it)
                                    zip.closeEntry()
                                }
                            }
                        }
                        entry.name.startsWith("downloads/") -> {
                            val fileName = entry.name.substring("downloads/".length)
                            if (fileName.matches(Regex("\\d+\\.json"))) {
                                FileOutputStream(File(configsPath, fileName)).use {
                                    if (entry.size > 200.KB) throw RuntimeException(
                                        "Over-sized $fileName (${formatSize(entry.size)} = ${entry.size} bytes)"
                                    )
                                    zip.copyTo(it)
                                    zip.closeEntry()
                                }
                            }
                        }
                        else -> {
                            if (!isInternalRestore)
                                restoreSync(FileInputStream(currentDataBackup), true)
                            return false
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            currentDataBackup.delete()
            return true
        } catch (e: Exception) {
            log("Restore data", e)
            if (!isInternalRestore)
                restoreSync(FileInputStream(currentDataBackup), true)
        }
        return false
    }

    private fun launchFilePicker(
        action: String,
        requestCode: Int,
        mimeType: String = "application/zip"
    ) = try {
        val intent = Intent(action).apply {
            if (action == Intent.ACTION_OPEN_DOCUMENT || action == Intent.ACTION_CREATE_DOCUMENT) {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
            }
        }
        startActivityForResult(intent, requestCode)
    } catch (e: Exception) {
        showSnackBar("Can't launch file picker", duration = Snackbar.LENGTH_SHORT)
    }

    fun showRemainingTime(view: View) {
        Pref.showRemainingTimeInNotification = !showRemainingTime.isChecked
        showRemainingTime.isChecked = Pref.showRemainingTimeInNotification
    }
}
