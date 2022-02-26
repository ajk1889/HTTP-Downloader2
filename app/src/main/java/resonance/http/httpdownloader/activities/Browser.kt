package resonance.http.httpdownloader.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import kotlinx.android.synthetic.main.activity_browser.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.adapters.TabsAdapter
import resonance.http.httpdownloader.adapters.WebTabAdapter
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.nuLliTy
import resonance.http.httpdownloader.fragments.BrowserOptions
import resonance.http.httpdownloader.fragments.OverlayFragmentParent
import resonance.http.httpdownloader.fragments.SearchSuggestions
import resonance.http.httpdownloader.fragments.TabsList
import resonance.http.httpdownloader.helpers.*

class Browser : ParentActivity() {
    lateinit var downloadFolder: String
    lateinit var transferServiceConnection: TransferServiceConnection

    lateinit var webTabAdapter: WebTabAdapter
    var dialog: AlertDialog? = null

    companion object {
        lateinit var tabsAdapter: TabsAdapter
        lateinit var instance: Browser
        var isActivityPaused = false
        var ignoreSslErrorsForThisSession = false
        val onActivityResumeCallbacks = mutableListOf<() -> Unit>()
        enum class FromOptions { MAIN, ADVANCED, SIMPLE, SETTINGS, FILE_JOINER }

        const val FOLDER_CHOOSE = 1280

        fun start(
            context: Activity,
            from: FromOptions?,
            url: String? = null,
            request: String? = null
        ) {
            val intent = Intent(context, Browser::class.java)
            intent.putExtra(C.misc.from, from)
            if (url != null) intent.putExtra(C.dt.url, url)
            if (request != null) intent.putExtra(C.misc.request, request)
            context.startActivity(intent)
            log(
                "Browser",
                "start: context=$context, from=$from, url=${url.nuLliTy()}, request=$request"
            )
        }
    }

    val currentTab get() = webTabAdapter.currentTab

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        log("Browser", "onCreate: called")
        setContentView(R.layout.activity_browser)
        downloadFolder = C.defaultOutputFolder

        transferServiceConnection = TransferServiceConnection(this)
        tabsAdapter = TabsAdapter(this)
        webTabAdapter = WebTabAdapter(this, tabsAdapter).apply {
            onDownloadIntercept = intent.getStringExtra(C.misc.request) ?: onDownloadIntercept
            log("Browser", "onCreate: onDownloadIntercept = $onDownloadIntercept")
        }
        if (Pref.forceSingleTabMode) tabsIcon.setGone()

        if (!intent.hasExtra(C.dt.url)) {
            log("Browser", "onCreate: no url specified; opening home page")
            webTabAdapter.addNewTab(Pref.browserHome)
        }

        onNewIntent(intent)
    }

    var from: FromOptions? = null
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        log(
            "Browser", "onNewIntent: ",
            "from=${intent?.extras?.get(C.misc.from)}",
            "keys=" + intent?.extras?.keySet()?.toList()
        )
        val from = intent?.extras?.get(C.misc.from)
        if (from != null) this.from = from as FromOptions
        intent?.getStringExtra(C.misc.request)?.also {
            webTabAdapter.onDownloadIntercept = it
            webTabAdapter.forAll { tab -> tab.onDownloadIntercept = it }
        }

        val url = intent?.getStringExtra(C.dt.url) ?: return
        val index = webTabAdapter.indexOf(url)
        log("Browser", "onNewIntent: requested url is in $index")
        if (index == -1)
            webTabAdapter.addNewTab(url)
        else webTabAdapter.setCurrentItem(index)
    }

    var onActivityResultListener: ((Uri) -> Unit)? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FOLDER_CHOOSE && resultCode == Activity.RESULT_OK) data?.data?.also {
            contentResolver.takePersistableUriPermission(it, data.getRWFlags())
            downloadFolder = "${C.type.uri}:$it"
            onActivityResultListener?.invoke(it)
        } else if (requestCode == 8262 && resultCode == Activity.RESULT_OK) {
            // Request Code for QrCodeActivity start is 8462
            data?.getStringExtra(C.misc.qrScanResult)?.also {
                webTabAdapter.addNewTab(it)
            }
        }
    }

    override fun onBackPressed() {
        if (overlayFragment != null && overlayFragment?.isShowing == true) {
            log("Browser", "onBackPressed: dismissing overlay")
            overlayFragment?.dismiss(fragmentHost, this@Browser)
            overlayFragment = null
        } else if (snackBar != null) {
            dismissSnackBar()
        } else if (!currentTab.onBackPressed()) {
            exitToSource()
        }
    }

    fun exitToSource() {
        val from = this.from
        log("Browser", "onBackPressed: exitToSource: from=$from")
        if (from == null) {
            super.onBackPressed()
            return
        }
        val intent = when (from) {
            FromOptions.MAIN -> Intent(this, MainActivity::class.java)
            FromOptions.ADVANCED -> Intent(this, AdvancedDownload::class.java)
            FromOptions.SIMPLE -> Intent(this, SimpleActivity::class.java)
            FromOptions.SETTINGS -> Intent(this, SettingsActivity::class.java)
            FromOptions.FILE_JOINER -> Intent(this, FileJoiner::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        finish()
    }

    var overlayFragment: OverlayFragmentParent? = null
    fun showTabs(v: View) {
        log("Browser", "showTabs: adding overlay")
        overlayFragment = TabsList()
        overlayFragment!!.show(fragmentHost, this)
    }

    fun showOptions(v: View) {
        log("Browser", "showOptions: adding overlay")
        overlayFragment = BrowserOptions()
        overlayFragment!!.show(fragmentHost, this)
    }

    fun showSearchBar(v: View) {
        log("Browser", "showSearchBar: adding overlay")
        overlayFragment = SearchSuggestions()
        overlayFragment!!.show(fragmentHost, this)
    }

    override fun onPause() {
        isActivityPaused = true
        log("Browser", "onPause: called")
        webTabAdapter.forAll { it.webView?.onPause() }
        super.onPause()
    }

    override fun onStop() {
        dialog?.dismiss()
        super.onStop()
    }

    override fun onResume() {
        isActivityPaused = false
        instance = this
        super.onResume()
        log("Browser", "onResume: called")
        if (MainActivity.hasExited) {
            MainActivity.hasExited = false
            finish(); return
        }
        onActivityResumeCallbacks.forEach { it() }
        onActivityResumeCallbacks.clear()
        webTabAdapter.forAll {
            it.webView?.apply {
                settings.javaScriptCanOpenWindowsAutomatically = !Pref.blockBrowserPopup
                onResume()
            }
        }
    }

    override fun onDestroy() {
        log("Browser", "onDestroy: called")
        webTabAdapter.forAll { it.webView?.destroy() }
        super.onDestroy()
    }
}