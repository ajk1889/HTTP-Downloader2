package resonance.http.httpdownloader.fragments

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.room.Room
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_browser.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.activities.SettingsActivity
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.helpers.db.*
import resonance.http.httpdownloader.implementations.TransferWrapper
import java.io.File
import java.net.URL
import kotlin.math.max
import kotlin.math.min

class WebTab : Fragment() {
    private var onWebViewInitialized: ((WebView) -> Unit)? = null

    //if this tab is created by another browser tab, this field is used to return to the origin onBackPressed
    private var originTabIndex: Int = -1
    var onIconReceived: ((Bitmap?) -> Unit)? = null

    //These list of functions get executed when loading stops. This may include loading errors too.
    var onLoadingFinished: MutableList<() -> Unit> = mutableListOf()

    private val browser = Browser.instance
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    var webView: WebView? = null
    lateinit var progressBar: ProgressBar
    var isDesktopMode = Pref.preferDesktopMode
    private var initialUrl: String = ""
    private var initialReferer: String? = null
        get() = field.also { if (field != null) field = null }

    var onDownloadIntercept = C.misc.startDownload

    var position = -1
    var address = ""
        set(value) {
            field = value
            if (browser.webTabAdapter.current == position) {
                browser.address.text = value
                browser.address.post {
                    if (browser.overlayFragment is SearchSuggestions)
                        browser.overlayFragment?.dismiss(browser.fragmentHost, browser)
                }
            }
        }

    private val historyDB: HistoryDbConn by lazy {
        Room.databaseBuilder(
            browser.applicationContext,
            HistoryDB::class.java, "browserHistory"
        ).build().conn()
    }
    private val offlinePagesDB: SavedPagesDbConn by lazy {
        Room.databaseBuilder(
            browser.applicationContext,
            SavedPagesDB::class.java, "savedPages"
        ).build().conn()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_web_tab, container, false).also {
            webView = it.findViewById(R.id.webView)
            progressBar = it.findViewById(R.id.progress)
            initializeWebView()
            loadURL(initialUrl)

            //Some times browser doesn't start loading immediately.
            //So a loading status need to be artificially simulated to please the user
            isLoading = true; progressBar.progressCompat = 5
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        queuedURL?.also { loadURL(it) }
        queuedURL = null
    }

    fun getTitle(length: Int, replaceSlash: Boolean = true, default: String = initialUrl): String {
        var title = webView?.title.let {
            if (it.isNullOrBlank()) webView?.url ?: default else it
        }
        if (title.startsWith("https://")) title = title.substring(8)
        else if (title.startsWith("http://")) title = title.substring(7)
        if (replaceSlash) title = title.replace("/", "_")
        if (title.length > length) title = title[0 to length]
        if (title.endsWith("_") || title.endsWith("/"))
            title = title[0 to title.length - 1]
        return title
    }

    var isLoading = true

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        CookieManager.getInstance().setAcceptCookie(true)

        webView!!.let { webView ->
            with(webView.settings) {
                databaseEnabled = true
                domStorageEnabled = true
                javaScriptEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically =
                    !Pref.forceSingleTabMode && !Pref.blockBrowserPopup
                setSupportMultipleWindows(!Pref.forceSingleTabMode)
                setSupportZoom(true)
                if (isDesktopMode) {
                    userAgentString = if (Pref.userAgentName !in listOf("Windows", "Ubuntu", "Mac"))
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/" +
                                "537.36 (KHTML, like Gecko) Chrome/80.0.3987.132 Safari/537.36"
                    else Pref.customUserAgent
                    loadWithOverviewMode = true
                    useWideViewPort = true
                } else {
                    userAgentString = Pref.customUserAgent
                    loadWithOverviewMode = false
                    useWideViewPort = false
                }
            }

            webView.setDownloadListener { url, userAgent, contentDisposition, _, _ ->
                val obj = JSONObject().apply {
                    put(C.misc.from, C.misc.browser)
                    put(C.dt.type, DownloadObject.TYPE)
                    put(C.dt.url, url)
                    put(C.dt.id, getUniqueId())
                    val cookies = CookieManager.getInstance().getCookie(url)
                    val referer = this@WebTab.webView!!.url
                    val headers = """${if (cookies != null) "Cookie: $cookies" else ""}
                        |User-Agent: ${if (userAgent.trim() == "") "HTTP Downloader" else userAgent}
                        |${if (referer != null) "Referer: $referer" else ""}""".trimMargin().trim()
                    put(C.dt.headers, headers)
                    if (contentDisposition.contains("filename=\"")) {
                        var fileName = contentDisposition
                            .split("filename=\"")[1]
                            .split("\"")[0].asSanitizedFileName()
                        if (!Pref.appendConflictingFiles)
                            while (filePreExists(fileName, browser.downloadFolder, browser))
                                fileName = renameDuplicateFile(fileName)
                        put(C.dt.fileName, fileName)
                    }
                }
                log("WebTab", "downloadIntercepted: onDownloadIntercept=$onDownloadIntercept")
                if (onDownloadIntercept.startsWith(C.misc.refresh)) {
                    val id = onDownloadIntercept.substring(C.misc.refresh.length).toLong()
                    val transfer = TransferWrapper.loadTransfer(browser, id)
                    verifyAndStartDownload(transfer, obj)
                } else when (onDownloadIntercept) {
                    C.misc.saveForAdvancedMode -> {
                        Pref.lastAdvancedDownloadData = obj
                        browser.exitToSource()
                    }
                    C.misc.startDownload -> {
                        if (Pref.promptDownloadName) {
                            showDownloadNamePrompt(obj)
                        } else startDownload(obj)
                    }
                    C.misc.saveToPreferences -> {
                        Pref.lastBrowserDownloadData = obj
                        browser.exitToSource()
                    }
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.progressCompat = max(newProgress, 5)
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    super.onReceivedIcon(view, icon)
                    onIconReceived?.invoke(icon)
                    onIconReceived = null

                    if (view != null) view.url?.also { updateIco(it, icon) }
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (Pref.forceSingleTabMode)
                        return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                    browser.webTabAdapter.addNewTab(null, view?.url)
                    browser.currentTab.originTabIndex = position
                    browser.currentTab.runOnInitializedWebView { webView ->
                        (resultMsg?.obj as WebView.WebViewTransport?)?.webView = webView
                        resultMsg?.sendToTarget()
                    }
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    super.onCloseWindow(window)
                    browser.webTabAdapter.removeTab(position)
                }
            }
            webView.webViewClient = object : WebViewClient() {
                fun getAdditionalHeaders(url: String) = mutableMapOf<String, String>().apply {
                    val referer = (initialReferer ?: webView.url)
                    if (!referer.isNullOrBlank()) this["Referer"] = referer
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrBlank()) this["Cookie"] = cookies
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    if (request == null || !request.url.isAbsolute || !listOf(
                            "http",
                            "https"
                        ).contains(request.url.scheme)
                    ) return false
                    request.url.str.also {
                        address = it
                        webView.loadUrl(it, getAdditionalHeaders(it))
                    }
                    progressBar.progressCompat = 5
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    address = url
                    webView.loadUrl(url, getAdditionalHeaders(url))
                    progressBar.progressCompat = 5
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    silently {
                        if (url != null && Pref.isMegaFirstTime && URL(url).host.contains("mega.nz")) {
                            browser.showSnackBar("HTTP-Downloader doesn't support downloads from Mega")
                            Pref.isMegaFirstTime = false
                        }
                    }
                    isLoading = true; progressBar.progressCompat = 5; address = url ?: address
                    addToHistoryDB()
                    if (::updatePageTitle !in onLoadingFinished)
                        onLoadingFinished.add(::updatePageTitle)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    isLoading = false; progressBar.progressCompat = 0

                    // invoking callbacks
                    for (fn in onLoadingFinished) fn()
                    onLoadingFinished.clear()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    log(
                        "WebTab",
                        "onReceivedHttpError: ",
                        errorResponse?.statusCode,
                        errorResponse?.reasonPhrase,
                        request?.url
                    )
                    isLoading = false; progressBar.progressCompat = 0
                    for (fn in onLoadingFinished) fn()
                    onLoadingFinished.clear()

                    // showing prompt to sign in in case of error code 403
                    if (errorResponse?.statusCode == 403 && request?.url.toString() == webView.url) {
                        browser.showSnackBar(
                            "This website forbids you accessing this page. <b>Try signing in</b>"
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    log(
                        "WebTab",
                        "ssl error; Pref.ignoreSslErrors=${Pref.ignoreSslErrors}; " +
                                "ignoreSslErrorsForThisSession=${Browser.ignoreSslErrorsForThisSession}"
                    )
                    if (!Pref.ignoreSslErrors && !Browser.ignoreSslErrorsForThisSession) {
                        super.onReceivedSslError(view, handler, error)
                        isLoading = false; progressBar.progressCompat = 0
                        for (fn in onLoadingFinished) fn()
                        onLoadingFinished.clear()
                        browser.showSnackBar(
                            "SSL error encountered while loading page. " +
                                    "Do you want to ignore and proceed (unsafe)?",
                            "Yes",
                            duration = Snackbar.LENGTH_LONG,
                            onClick = {
                                browser.showLongToast("Enable 'Ignore SSL errors' from settings")
                                val extras =
                                    Intent().apply { putExtra(C.misc.childToScrollTo, 1200) }
                                SettingsActivity.start(browser, Browser::class.java.name, extras)
                            }
                        )
                    } else handler?.proceed()
                }

                @TargetApi(Build.VERSION_CODES.M)
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    log(
                        "WebTab",
                        "onReceivedError:",
                        error?.errorCode,
                        error?.description,
                        request?.url
                    )
                    isLoading = false; progressBar.progressCompat = 0
                    for (fn in onLoadingFinished) fn()
                    onLoadingFinished.clear()
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    url: String?
                ) {
                    super.onReceivedError(view, errorCode, description, url)
                    log("WebTab", "ReceivedError", errorCode, description, url)
                    isLoading = false; progressBar.progressCompat = 0
                    for (fn in onLoadingFinished) fn()
                    onLoadingFinished.clear()
                }
            }
        }
        onWebViewInitialized?.invoke(webView!!)
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showDownloadNamePrompt(obj: JSONObject) {
        val view = browser.layoutInflater.inflate(R.layout.download_location, null)
        val folderNameText = view.findViewById<TextView>(R.id.folderName)
        val fileNameText = view.findViewById<EditText>(R.id.fileName)
        val warningIcon = view.findViewById<ImageView>(R.id.warning)
        if (!obj.has(C.dt.fileName)) silently {
            var name = URL(obj.getString(C.dt.url)).file.split("?")[0]
            if (name.startsWith('/')) name = name.substring(1)
            if (name.endsWith('/')) name = name.substring(0, name.length - 1)
            if (name.contains("/")) name = name.substring(name.lastIndexOf("/"))
            fileNameText.setText(name.asSanitizedFileName())
        } else fileNameText.setText(obj.getString(C.dt.fileName))
        if (browser.downloadFolder.startsWith(C.type.file))
            folderNameText.text = "Internal storage / HTTP-Downloads"
        else setFolderName(browser.downloadFolder.toUri(), folderNameText)

        fileNameText.setOnTextChangedListener {
            uiScope.launch {
                if (it == null) return@launch
                if (filePreExist(it.str)) warningIcon.unHide()
                else warningIcon.setGone()
            }
        }
        folderNameText.setOnClickListener {
            browser.onActivityResultListener = {
                uiScope.launch {
                    var fileName = fileNameText.text.str
                    if (!Pref.appendConflictingFiles)
                        while (filePreExist(fileName))
                            fileName = renameDuplicateFile(fileName)
                    fileNameText.setText(fileName)
                    setFolderName(it, folderNameText)
                }
            }
            try {
                browser.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    Browser.FOLDER_CHOOSE
                )
            } catch (e: Exception) {
                browser.showSnackBar("Can't launch file picker", duration = Snackbar.LENGTH_SHORT)
            }
        }
        warningIcon.setOnClickListener {
            view.findViewById<TextView>(R.id.warningText).run {
                unHide()
                handler.postDelayed(this::setGone, 4000)
            }
        }
        val showDialog = {
            browser.dialog = AlertDialog.Builder(browser)
                .setTitle("Start download")
                .setView(view)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    obj.put(C.dt.fileName, fileNameText.text.str)
                    obj.put(C.dt.outputFolder, browser.downloadFolder)
                    startDownload(obj)
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        }
        if (Browser.isActivityPaused)
            Browser.onActivityResumeCallbacks.add(showDialog)
        else showDialog()
    }

    private suspend fun filePreExist(fileName: String) = withContext(Dispatchers.IO) {
        filePreExists(fileName, browser.downloadFolder, browser)
    }

    private fun startDownload(obj: JSONObject) {
        obj.put(C.dt.isCollapsed, false)
        Intent(C.filter.START_DOWNLOAD).apply {
            putExtra(C.misc.downloadDataJSON, obj.str)
            browser.transferServiceConnection.request(this)
            browser.showSnackBar("Download started", "view", Snackbar.LENGTH_LONG) {
                val intent = Intent(browser, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                browser.startActivity(intent)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setFolderName(uri: Uri, textView: TextView) {
        val folder = DocumentFile.fromTreeUri(browser, uri) ?: return
        if (folder.isFile || folder.name == null) return
        textView.text = "${folder.name}/"
    }

    private fun verifyAndStartDownload(
        data: TransferWrapper,
        obj: JSONObject
    ) {
        fun showInfo(info: String) = browser.showSnackBar(info, duration = Snackbar.LENGTH_LONG)
        fun compare(b1: ByteArray, b2: ByteArray): Boolean {
            val len = min(b1.size, b2.size)
            for (i in 0 until len)
                if (b1[i] != b2[i]) return false
            return true
        }

        fun promptForNewDownload(name: String?) {
            val showDialog = {
                val msg = "Contents of this file (<b><i>$name</i></b>) doesn't " +
                        "match your previous download.<br><br>" +
                        "Should this be started <b>separately as a new task?</b>"
                browser.dialog = AlertDialog.Builder(browser)
                    .setTitle("File mismatch")
                    .setMessage(msg.asHtml())
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                        if (Pref.promptDownloadName) {
                            showDownloadNamePrompt(obj)
                        } else startDownload(obj)
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            if (Browser.isActivityPaused)
                Browser.onActivityResumeCallbacks.add(showDialog)
            else showDialog()
        }

        val newFileName: String? = obj.optString(C.dt.fileName)
        if (obj.has(C.dt.fileName))
            obj.remove(C.dt.fileName)
        data.updateCoreData(browser, obj)

        browser.showSnackBar("Validating your download...")

        val download = data.initializeTransfer() as DownloadObject
        download.onFetchFailed = { _, e ->
            showInfo("Could not initiate download: " + getExceptionCause(e, "webTab"))
        }
        download.onFetchSuccess = { _, response ->
            if (response != null) {
                when {
                    response.bytes.isEmpty() ->
                        showInfo("Could not initiate download: Server didn't send any data")
                    compare(response.bytes, read50KbFromOutput(data)) -> {
                        data.saveState(browser)

                        val intent = Intent(browser, MainActivity::class.java)
                        intent.putExtra(C.misc.from, "browser::refresh")
                        intent.putExtra(C.dt.id, data.id)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        browser.startActivity(intent)
                        browser.finish()
                    }
                    else -> browser.handler.post {
                        browser.dismissSnackBar()
                        promptForNewDownload(newFileName)
                    }
                }
            } else showInfo("Could not initiate download: Server didn't send any data")
        }
        log("WebTab", "verifyAndStartDownload: validating download")
        download.startFetchingDetails(50.KB)
    }

    private fun read50KbFromOutput(data: TransferWrapper) = {
        getOutputObject(browser, data).read(50.KB)
    } otherwise ByteArray(0)

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
    }

    fun onBackPressed(): Boolean {
        return with(webView ?: return false) {
            if (canGoBack()) {
                goBack(); true
            } else if (originTabIndex != -1 && originTabIndex < browser.webTabAdapter.count) {
                browser.webTabAdapter.setCurrentItem(originTabIndex)
                browser.webTabAdapter.removeTab(position)
                true
            } else false
        }
    }

    override fun toString(): String {
        return "WebTab at $position url: ${webView?.url}"
    }

    fun runOnInitializedWebView(fn: (WebView) -> Unit) {
        if (webView == null) onWebViewInitialized = fn
        else fn.invoke(webView!!)
    }

    private var queuedURL: String? = null
    fun loadURL(url: String) {
        if (url == "") return
        val modifiedURL = url.asSanitizedUrl()
        if (webView != null) {
            address = modifiedURL; webView!!.loadUrl(modifiedURL)
            progressBar.progressCompat = 5
        } else queuedURL = modifiedURL
        log(
            "WebTab",
            "Called loadURL with $url",
            if (url != modifiedURL) "Modified to $modifiedURL" else ""
        )
    }

    fun goForward() {
        if (webView?.canGoForward() == true) webView?.goForward()
        else browser.showSnackBar("Can't go forward any more", duration = Snackbar.LENGTH_SHORT)
    }

    fun goBackward() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else browser.showSnackBar("Can't go back any more", duration = Snackbar.LENGTH_SHORT)
    }

    fun reload() {
        webView?.reload() ?: browser.showSnackBar(
            "Couldn't reload this page",
            duration = Snackbar.LENGTH_SHORT
        )
    }

    fun cancel() {
        webView?.stopLoading()
    }

    private var lastAddedItem: HistoryItem? = null
    fun addToHistoryDB() {
        val web = webView ?: return
        val url = web.url ?: return
        if (url == lastAddedItem?.url) return

        val title = web.title ?: "Unknown page"
        HistoryItem(title, url, now()).also {
            lastAddedItem = null
            silentlyInBackground {
                it.id = historyDB.insert(it)
                lastAddedItem = it
            }
        }
    }

    fun updatePageTitle() {
        val last = lastAddedItem
        val url = webView?.url ?: return
        val title = webView?.title ?: return
        silentlyInBackground {
            if (last == null) historyDB.setTitle(url, title)
            else historyDB.setTitle(last.id, title)
        }
    }

    fun updateIco(url: String, ico: Bitmap?) {
        if (ico == null) return
        val last = lastAddedItem
        silentlyInBackground {
            if (last == null) historyDB.setIco(url, ico.toBase64())
            else historyDB.setIco(last.id, ico.toBase64())
        }
    }

    fun savePage() {
        val webView = webView ?: return
        val title = getTitle(30)
        val base = File(browser.getExternalFilesDir(null), "saved-pages").apply { mkdirs() }
        var pagesPath = File(base, "$title.mht")
        while (pagesPath.exists()) pagesPath = File(base, renameDuplicateFile(pagesPath.name))

        val item = SavedPageItem(
            title = webView.title ?: "Unknown page",
            path = pagesPath.absolutePath,
            time = now(),
            ico = webView.favicon?.toBase64()
        )
        webView.saveWebArchive(pagesPath.absolutePath, false) {
            browser.showSnackBar("Page saved locally", duration = Snackbar.LENGTH_SHORT)
            silentlyInBackground { offlinePagesDB.insert(item) }
        }
    }

    var ProgressBar.progressCompat: Int
        get() = progress
        set(value) {
            progressBar.layoutParams = progressBar.layoutParams.apply {
                height = if (value in 1..99) -2 else 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                setProgress(value, true)
            else progress = value
        }

    private fun silentlyInBackground(function: () -> Unit) = ioScope.launch { silently(function) }

    companion object {
        @JvmStatic
        fun newInstance(index: Int, url: String?, referer: String? = null) = WebTab().apply {
            position = index; initialUrl = url ?: ""; initialReferer = referer
        }
    }
}
