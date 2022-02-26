package resonance.http.httpdownloader.activities

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import resonance.http.httpdownloader.ApplicationClass
import resonance.http.httpdownloader.BuildConfig
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.adapters.DownloadsListAdapter
import resonance.http.httpdownloader.broadcastReceivers.DownloadStatusChangeReceiver
import resonance.http.httpdownloader.broadcastReceivers.ProgressUpdateReceiver
import resonance.http.httpdownloader.broadcastReceivers.SnackBarReceiver
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.fragments.MainMenu
import resonance.http.httpdownloader.fragments.OverlayFragmentParent
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.TransferWrapper
import resonance.http.httpdownloader.services.TransferService
import java.io.File


class MainActivity : ParentActivity() {
    lateinit var adapter: DownloadsListAdapter
    lateinit var transferServiceConnection: TransferServiceConnection
    private lateinit var animFactory: AnimFactory
    private lateinit var broadcastManager: LocalBroadcastManager
    //broadcast receivers
    private lateinit var progressUpdateReceiver: ProgressUpdateReceiver
    private lateinit var snackBarReceiver: SnackBarReceiver
    private lateinit var statusChangeReceiver: DownloadStatusChangeReceiver

    var overlayFragment: OverlayFragmentParent? = null //to show options fragments

    val doneListeners = mutableMapOf<String, (Intent) -> Unit>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        hasExited = false

        if (IntroActivity.incompleteStep(this) != null) {
            startActivity(Intent(this, IntroActivity::class.java))
            hasExited = true
            finish(); return
        }

        //by default internal storage is used as download location
        if (!Pref.key.useInternal.exists()) {
            Pref.useInternal = true
            Pref.downloadLocation = C.INTERNAL_DOWNLOAD_FOLDER.absolutePath
            log("MainActivity", "onCreate: Setting download location to " + Pref.downloadLocation)
        }
        setContentView(R.layout.activity_main)
        log("MainActivity", "oCr:")

        adapter = DownloadsListAdapter(this)
        animFactory = AnimFactory()
        broadcastManager = LocalBroadcastManager.getInstance(this)
        transferServiceConnection = TransferServiceConnection(this)

        progressUpdateReceiver = ProgressUpdateReceiver(this)
        snackBarReceiver = SnackBarReceiver(this)
        statusChangeReceiver = DownloadStatusChangeReceiver(this)

        downloads_list.adapter = adapter

        onNewIntent(intent)

        ioScope.launch {
            val componentName = ComponentName(this@MainActivity, TestActivity::class.java)
            val params = File(getExternalFilesDir(null), "params.json")
            packageManager.setComponentEnabledSetting(
                componentName,
                if (BuildConfig.DEBUG || params.exists())
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            val count = ApplicationClass.logDB.clearOlderThan(now() - 7.days)
            log("MainActivity", "onCreate: deleted $count old items from logDB")
            val file = File(getExternalFilesDir(null), "logs")
            if (file.exists()) log(
                "MainActivity",
                "onCreate: log file exists in external directory",
                "deleted=" + file.delete()
            )
        }

        if (--Pref.reviewRequestCounter == 0) {
            Pref.reviewRequestCounter = 10
            showReviewRequest()
        }

        val newVersionCode = BuildConfig.VERSION_CODE
        val oldVersionCode = Pref.appVersion
        if (oldVersionCode != newVersionCode) {
            log("ApplicationClass", "app updated: new=$newVersionCode; old=$oldVersionCode")
            Pref.appVersion = newVersionCode
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        log("MainActivity", "onNewIntent: intent = " + intent?.extras?.keySet()?.toList())
        if (intent == null) return
        if (intent.getBooleanExtra(C.misc.pauseAll, false))
            pauseAll()
        else if (intent.getStringExtra(C.misc.from) == "browser::refresh") {
            val id = intent.getLongExtra(C.dt.id, -1)
            if (id == -1L) return
            val item = adapter.getTaskById(id) ?: return
            val req = Intent(C.filter.REQUEST)
            req.putExtra(C.misc.request, C.req.resumeStop)
            req.putExtra(C.dt.id, item.id)
            req.putExtra(C.misc.refresh, true)
            transferServiceConnection.request(req)
            log("MainActivity", "onNewIntent: sent broadcast $req")
        }
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
        log("MainActivity", "onPause: called")
        //need not show progress changes that frequently; To reduce power consumption
        Transfer.progressUpdateInterval = 600L
        broadcastManager.unregisterReceiver(progressUpdateReceiver)
        broadcastManager.unregisterReceiver(snackBarReceiver)
        broadcastManager.unregisterReceiver(statusChangeReceiver)
        areBroadcastReceiversAlive = false
        log("MainActivity", "onPause: all broadcast receivers unregistered")
    }

    override fun onStop() {
        dialog?.dismiss()
        super.onStop()
    }

    private var permissionRequestCount = 0
    override fun onResume() {
        instance = this
        isInForeground = true
        if (!hasPermission(this)) {
            if (permissionRequestCount < 2) {
                log("MainActivity", "onResume: Permission not granted. Requesting")
                toast?.also { it.cancel() }
                permissionRequestCount += 1
                if (isSdk29Plus()) {
                    startActivity(Intent(this, IntroActivity::class.java))
                    hasExited = true
                    finish()
                } else ActivityCompat.requestPermissions(this, permissions, 250)
            } else toast = showLongToast("Storage permission is mandatory for downloading files")
        }
        super.onResume()
        hasExited = false
        log("MainActivity", "onResume:")

        //Reverting frequency of progress update
        Transfer.progressUpdateInterval = 300L
        broadcastManager.registerReceiver(
            progressUpdateReceiver,
            IntentFilter(C.filter.PROGRESS_UPDATE)
        )
        broadcastManager.registerReceiver(snackBarReceiver, IntentFilter(C.filter.SHOW_MSG))
        broadcastManager.registerReceiver(
            statusChangeReceiver,
            IntentFilter().apply {
                addAction(C.filter.STATUS_CHANGE)
                addAction(C.filter.DONE)
            }
        )
        log("MainActivity", "onResume: registered all broadcast receivers")

        initializeDownloadsList()
    }

    private var lastBackPress = 0L
    private val exitToast: Toast by lazy {
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT)!!
    }
    override fun onBackPressed() {
        log("MainActivity", "onBackPressed:")
        when {
            overlayFragment != null -> {
                overlayFragment?.dismiss(fragmentHost, this)
                overlayFragment = null
                log("MainActivity", "onBackPressed: dismissed overlay")
            }
            snackBar != null -> dismissSnackBar()
            now() - lastBackPress < 600 -> {
                exitToast.cancel()
                hasExited = true
                super.onBackPressed()
                log("MainActivity", "onBackPressed: exit")
            }
            else -> {
                lastBackPress = now()
                exitToast.show()
            }
        }
    }

    private var sharedData: JSONObject? = null
    var inaccessibleId: Long = -1
    private var sharedTransfer: TransferWrapper? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        log(
            "MainActivity", "onActivityResult: ",
            "requestCode=$requestCode",
            "resultCode=$resultCode",
            "data.data=" + data?.data.nuLliTy(),
            "data.clipData.cout=" + data?.clipData?.itemCount,
            "data.extras=" + data?.extras?.keySet()
        )
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SHARED_DATA_QR -> {
                if (resultCode == Activity.RESULT_OK)
                    data?.getStringExtra(C.misc.qrScanResult)?.also { processSharedData(it) }
            }
            FILE_NOT_ACCESSIBLE -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.data?.also { checkAndUpdateLocation(inaccessibleId, it) }
                } else showSnackBar("No folder selected, restart download manually")
            }
            SELECT_OP_FOLDER -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    val shared = sharedData
                    log(
                        "MainActivity",
                        "onActivityResult:",
                        "uri = ${uri.nuLliTy()}",
                        "shared= ${shared.nuLliTy()}"
                    )
                    if (shared == null || uri == null) return
                    contentResolver.takePersistableUriPermission(uri, data.getRWFlags())
                    shared.put(C.dt.outputFolder, "${C.type.uri}:$uri")
                    Pref.useInternal = false
                    Pref.downloadLocation = uri.str

                    val conflict =
                        getConflictingFile("${C.type.uri}:$uri", shared.getString(C.dt.fileName))
                    log(
                        "MainActivity",
                        "onActivityResult: conflicting file = ${conflict.nuLliTy()}"
                    )
                    if (conflict == null) {
                        val wrapper = TransferWrapper(shared)
                        adapter.insert(wrapper, 0)
                        blankInfo.setGone()
                        sharedTransfer = wrapper
                        transferServiceConnection.request(Intent(C.filter.START_DOWNLOAD).apply {
                            shared.put(C.dt.isCollapsed, false)
                            putExtra(C.misc.downloadDataJSON, shared.str)
                        })
                        log(
                            "MainActivity",
                            "onActivityResult: start shared download broadcast sent"
                        )
                    } else showSnackBar("Selected folder already contains a file named $conflict. Retry with another folder")
                } else showSnackBar("No folder selected.\nFailed to Restore shared data")
            }
        }
    }

    private var toast: Toast? = null
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == 250) {
            if (!hasPermission(this)) {
                toast?.also { it.cancel() }
                toast = showLongToast("Storage permission is mandatory for downloading files")
            } else if (!C.INTERNAL_DOWNLOAD_FOLDER.exists())
                C.INTERNAL_DOWNLOAD_FOLDER.mkdirs()
        } else if (!hasPermission(this)) {
            toast?.also { it.cancel() }
            toast = showLongToast("Storage permission is mandatory for downloading files")
        }
    }

    fun processSharedData(data: String) {
        fun parseDownloadData(toString: String): JSONObject {
            val source = JSONObject(toString)
            val destination = JSONObject()
            destination.put(C.dt.id, getUniqueId())
            destination.put(C.dt.type, source.getString(C.dt.type))
            destination.put(C.dt.fileName, source.getString(C.dt.fileName))
            destination.put(C.dt.url, source.getString(C.dt.url))
            destination.put(C.dt.headers, source.getString(C.dt.headers))
            destination.put(C.dt.emptyFirstMode, source.optBoolean(C.dt.emptyFirstMode, false))
            destination.put(C.dt.offset, source.optLong(C.dt.offset, 0))
            destination.put(C.dt.limit, source.optLong(C.dt.limit, -1))
            return destination
        }
        try {
            sharedData = parseDownloadData(data)
            val toast = showLongToast("Select output folder")
            try {
                with(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) {
                    startActivityForResult(this, SELECT_OP_FOLDER)
                }
            } catch (e: Exception) {
                toast.cancel()
                showSnackBar("Could not add this download. No file picker found")
            }
        } catch (e: JSONException) {
            showSnackBar("The download data you have entered is <b>invalid</b>. Please try again")
        }
    }

    private fun getConflictingFile(folderString: String, fileName: String): String? {
        log("MainActivity", "getConflictingFile: ", folderString, fileName)
        when {
            folderString.isUri() -> {
                val folder = DocumentFile.fromTreeUri(this, folderString.toUri()) ?: return null
                if (!folder.isDirectory)
                    throw RuntimeException("Unreachable point 2: The treeUri points to a file")
                for (file in folder.listFiles())
                    if (file?.name == fileName) return fileName
                log("MainActivity", "getConflictingFile: no existing file found")
                return null
            }
            folderString.isFile() -> {
                val folder = folderString.toFolder()
                if (!folder.isDirectory)
                    throw RuntimeException("Unreachable point 2: The $folder is not a directory")
                for (file in folder.listFiles() ?: return null)
                    if (file.name == fileName) return fileName
                log("MainActivity", "getConflictingFile: no existing file found")
                return null
            }
            else -> return null
        }
    }

    private fun checkAndUpdateLocation(id: Long, uri: Uri) {
        log("MainActivity", "checkAndUpdateLocation: ", id)
        val item = adapter.findItemById(id)
        val file = DocumentFile.fromTreeUri(this, uri)?.findFile(item.fileName)
        log("MainActivity", "checkAndUpdateLocation: file is ", file.nuLliTy())
        if (file == null && item.written == 0L || file != null && file.isFile) {
            item.outputFolder = "${C.type.uri}:$uri"
            item.statusIcon = C.ico.blank; item.exceptionCause = null
            item.hasFailed = false; item.isPaused = true; item.isStopped = true
            item.pauseBtnIcon = C.ico.resume; item.stopBtnIcon = C.ico.restart
            item.isStatusProgressVisible = false
            item.saveState(this)
            adapter.notifyDataSetChanged()
            with(Intent(C.filter.REQUEST)) {
                putExtra(C.misc.request, C.req.reloadTask)
                putExtra(C.dt.id, item.id)
                broadcastManager.sendBroadcast(this)
            }
            log("MainActivity", "checkAndUpdateLocation: broadcast sent")
        } else showSnackBar(
            "<b>This folder doesn't seem to be valid</b><br>" +
                    "Choose the folder for previous download"
        )
    }


    fun showDownloadOptions(v: View) {
        log("MainActivity", "showDownloadOptions: showing overlay")
        if (overlayFragment != null) {
            overlayFragment?.dismiss(fragmentHost, this)
            return
        }
        overlayFragment = MainMenu()
        overlayFragment!!.show(fragmentHost, this)
//        sendTestDownloadBroadcast()
    }

    fun sendTestDownloadBroadcast() {
        Intent(C.filter.START_DOWNLOAD).apply {
            this.id = ++ApplicationClass.lastSentIntentId
            val downloadId = getUniqueId()
            val data = JSONObject(
                """{
                    |"type":"DownloadObject",
                    |"id":$downloadId,
                    |"emptyFirstMode":"false",
                    |"url":"http://localhost:1234/100mb.txt",
                    |"offset":${15.MB},
                    |"limit":${35.MB},
                    |"fileName":"a$downloadId.zip"
                |}""".trimMargin()
            )
            data.put(C.dt.isCollapsed, false)
            putExtra(C.misc.downloadDataJSON, data.str)
            adapter.insert(TransferWrapper(data), 0)
            blankInfo.setGone()
            transferServiceConnection.request(this)
            log("MainActivity", "sendTestDownloadBroadcast: sent")
        }
    }

    private fun initializeDownloadsList() {
        log("MainActivity", "initializeDownloadsList called")
        fun afterLoading(downloads: MutableList<TransferWrapper>) {
            log("MainActivity", "afterLoading: called with $downloads")
            downloads.sortByDescending { it.id }
            handler.post {
                wait.hide()
                //adding transfers to download list (adapter)
                adapter.clear()
                log("MainActivity", "afterLoading: cleared adapter")
                sharedTransfer?.also {
                    log("MainActivity", "afterLoading: adding sharedTransfer ($it)")
                    adapter.add(it)
                    sharedTransfer = null
                }

                adapter.addAll(downloads)
                log(
                    "MainActivity",
                    "afterLoading: Re-filled adapter. adapter.count=" + adapter.count
                )

                if (adapter.isEmpty) blankInfo.unHide()
                else blankInfo.setGone()
                areBroadcastReceiversAlive = true
            }
        }
        wait.unHide()
        log(
            "MainActivity",
            "initializeDownloadsList:",
            "TransferService.isRunning = " + TransferService.isRunning
        )
        if (TransferService.isRunning)
            loadFromService(::afterLoading)
        else loadFromDisk(::afterLoading)
    }

    private fun loadFromService(onEnd: (MutableList<TransferWrapper>) -> Unit) {
        log("MainActivity", "loadFromService: ")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                log("MainActivity", "loadFromService: onReceive: " + intent?.extras?.keySet())
                if (intent == null
                    || intent.action != C.filter.ALL_TRANSFERS
                    || intent.isDuplicate()
                ) return
                val items = intent.getStringArrayExtra(C.misc.allTransfers) ?: return
                log("MainActivity", "loadFromService: onReceive: invoking onEnd")
                onEnd(MutableList(items.size) {
                    TransferWrapper(JSONObject(items[it]))
                })
                broadcastManager.unregisterReceiver(this)
                log("MainActivity", "onReceive: unregistered broadcast")
            }
        }
        broadcastManager.registerReceiver(receiver, IntentFilter(C.filter.ALL_TRANSFERS))
        log("MainActivity", "loadFromService: Registered ${C.filter.ALL_TRANSFERS} receiver")

        val intent = Intent(C.filter.REQUEST)
        intent.putExtra(C.misc.request, C.req.sendAll)
        transferServiceConnection.request(intent)
        log("MainActivity", "loadFromService: Send ${C.req.sendAll} request")
    }

    private fun loadFromDisk(onEnd: (MutableList<TransferWrapper>) -> Unit) {
        log("MainActivity", "loadFromDisk: ")
        ioScope.launch {
            val downloads = TransferWrapper.loadTransfers(this@MainActivity)
            log("MainActivity", "loadFromDisk: loaded " + downloads.size + " items from disk")
            for (it in downloads) {
                if (it.id == 0L) {
                    it.id = getUniqueId()
                    log(
                        "MainActivity",
                        "loadFromDisk: item doesn't have valid id. Setting to ",
                        it.id
                    )
                    it.saveState(this@MainActivity)
                }
                if (!it.hasSucceeded) {
                    it.pauseBtnIcon = C.ico.resume; it.stopBtnIcon = C.ico.restart
                    it.speed = "---"; it.isStopped = true; it.isPaused = false
                }
            }
            log("MainActivity", "loadFromDisk: invoking onEnd")
            onEnd(downloads)
        }
    }

    fun pauseAll() {
        if (!TransferService.isRunning) {
            log("MainActivity", "pauseAll: nothing was running")
            showSnackBar("No ongoing tasks exist", duration = Snackbar.LENGTH_LONG)
            stopService(Intent(this, TransferService::class.java))
            return
        }
        log("MainActivity", "pauseAll: showing alert")
        dialog = AlertDialog.Builder(this)
            .setTitle("Pause all?")
            .setMessage(
                """Are you sure to pause all downloads?<br/><br/>
                    |<b>Note:</b> If you have any downloads which don't support pause & resume, 
                    |they will need to be restarted from beginning next time
                """.trimMargin().asHtml()
            )
            .setPositiveButton("Pause") { d, _ ->
                d.dismiss()
                val req = Intent(C.filter.REQUEST)
                req.putExtra(C.misc.request, C.req.pauseAll)
                transferServiceConnection.request(req)
                log("MainActivity", "pauseAll: done")
            }
            .setNegativeButton("Cancel") { d, _ ->
                log("MainActivity", "pauseAll: cancelled")
                d.dismiss()
            }
            .setOnDismissListener { log("MainActivity", "Pause all cancelled") }
            .setOnCancelListener { log("MainActivity", "Pause all cancelled") }
            .setCancelable(true)
            .show()
    }

    private fun showReviewRequest() {
        val pkgName = applicationContext.packageName
        dialog = AlertDialog.Builder(this)
            .setView(R.layout.review)
            .setPositiveButton("Rate") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$pkgName")
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    try {
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkgName").also {
                            startActivity(Intent(Intent.ACTION_VIEW, it))
                        }
                    } catch (e: Exception) {
                        showSnackBar(
                            "Could not detect play store",
                            duration = Snackbar.LENGTH_SHORT
                        )
                    }
                }
                Pref.reviewRequestCounter = Int.MAX_VALUE
            }
            .setNegativeButton("Never") { _, _ -> Pref.reviewRequestCounter = Int.MAX_VALUE }
            .setNeutralButton("Feedback") { _, _ ->
                contactUs(
                    subject = "Feedback: HTTP Downloader",
                    text = "Hi there,\ndescribe your feedback below\n========\n\n"
                )
                Pref.reviewRequestCounter = Int.MAX_VALUE
            }
            .setCancelable(true)
            .show()
    }

    companion object {
        lateinit var instance: MainActivity
        var areBroadcastReceiversAlive = true
        var hasExited = false
        var isInForeground = false

        const val FILE_NOT_ACCESSIBLE = 127
        const val SELECT_OP_FOLDER = 128
        const val SHARED_DATA_QR = 129

        private val ico2resMap = mapOf(
            C.ico.blank to R.drawable.blank,
            C.ico.stop to R.drawable.stop,
            C.ico.restart to R.drawable.restart,
            C.ico.resume to R.drawable.resume,
            C.ico.pending to R.drawable.pending,
            C.ico.done1 to R.drawable.done1,
            C.ico.done2 to R.drawable.done2,
            C.ico.warning to R.drawable.warning,
            C.ico.pause to R.drawable.pause,
            C.ico.open to R.drawable.open,
            C.ico.scheduled to R.drawable.schedule_blue
        )
        fun decodeImgRes(name: String): Int {
            return ico2resMap[name]
                ?: throw RuntimeException("Unknown image id: $name")
        }

        fun changeStatusBarColor(activity: Activity, color: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            log("MainActivity", "changeStatusBarColor: to", color)
            val window = activity.window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.parseColor(color)
        }

        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        fun hasPermission(ctx: Context): Boolean {
            return if (isSdk29Plus()) {
                if (Pref.useInternal)
                    return false
                val uri = try {
                    Uri.parse(Pref.downloadLocation)
                } catch (e: Exception) {
                    return false
                }
                try {
                    val folder = DocumentFile.fromTreeUri(ctx, uri)
                    !(folder == null || folder.isFile || folder.name == null)
                } catch (e: Exception) {
                    false
                }
            } else permissions.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
