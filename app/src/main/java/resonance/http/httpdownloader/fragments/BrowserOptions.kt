package resonance.http.httpdownloader.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_browser.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.*
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.AnimFactory
import resonance.http.httpdownloader.helpers.Pref
import resonance.http.httpdownloader.helpers.asSanitizedUrl
import resonance.http.httpdownloader.helpers.db.BookmarkDB
import resonance.http.httpdownloader.helpers.db.BookmarkDbConn
import resonance.http.httpdownloader.helpers.db.BookmarkItem
import resonance.http.httpdownloader.helpers.toBase64


class BrowserOptions : OverlayFragmentParent() {

    private val animFactory = AnimFactory()
    private val browser = Browser.instance

    val db: BookmarkDbConn by lazy {
        Room.databaseBuilder(
            browser.applicationContext,
            BookmarkDB::class.java, "bookmarks"
        ).build().conn()
    }
    val handler = Handler()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_browser_settings, container, false).apply {
            val currentTab = browser.currentTab
            findViewById<View>(R.id.overlay).setOnClickListener { dismiss() }
            setLoadingOption(currentTab, this)
            findViewById<View>(R.id.forward).setOnClickListener {
                log("BrowserOptions", "forward")
                dismiss(); currentTab.goForward()
            }
            findViewById<View>(R.id.backward).setOnClickListener {
                log("BrowserOptions", "backward")
                dismiss(); currentTab.goBackward()
            }
            findViewById<CheckBox>(R.id.desktopModeChkBx).isChecked = currentTab.isDesktopMode
            findViewById<CheckBox>(R.id.singleTabModeChkBx).isChecked = Pref.forceSingleTabMode
            findViewById<CheckBox>(R.id.desktopModeChkBx).also { checkBox ->
                checkBox.isClickable = false
                findViewById<View>(R.id.desktopMode).setOnClickListener { desktopMode(checkBox) }
            }
            findViewById<CheckBox>(R.id.singleTabModeChkBx)
                .setOnCheckedChangeListener { _, _ -> toggleSingleTabMode() }
            findViewById<View>(R.id.singleTabMode).setOnClickListener { toggleSingleTabMode() }
            findViewById<View>(R.id.savePage).setOnClickListener { savePage() }
            findViewById<View>(R.id.openInBrowser).setOnClickListener { openInBrowser() }
            findViewById<View>(R.id.shareLink).setOnClickListener { shareLink() }
            findViewById<View>(R.id.exitBrowser).setOnClickListener { exit() }
            findViewById<View>(R.id.history).setOnClickListener { history() }
            findViewById<View>(R.id.qrcode).setOnClickListener { scanQR() }
            findViewById<View>(R.id.settings).setOnClickListener { settings() }
            findViewById<View>(R.id.bookmarks).apply {
                val url = browser.currentTab.webView?.url
                val bookmarkBtn = findViewById<ImageView>(R.id.bookmark_button)
                if (url != null) {
                    ioScope.launch {
                        if (db.search(url).isNotEmpty()) post {
                            bookmarkBtn.setImageResource(R.drawable.bookmarked)
                            setOnClickListener { bookmarks(true) }
                        } else post {
                            bookmarkBtn.setImageResource(R.drawable.bookmark)
                            setOnClickListener { bookmarks(false) }
                        }
                    }
                } else setOnClickListener { bookmarks() }
            }

            with(findViewById<ScrollView>(R.id.options_layout)) {
                startAnimation(animFactory.menuExpandAnim(this, 425.dp, 170.dp))
            }
        }
    }

    private fun toggleSingleTabMode() {
        log("BrowserOptions", "singleTabMode change requested: " + Pref.forceSingleTabMode)
        dismiss()
        val title = if (Pref.forceSingleTabMode)
            "Enable multi-tab browsing?"
        else "Force single tab browsing?"
        AlertDialog.Builder(browser)
            .setTitle(title)
            .setMessage("Browser will be restarted and currently open page(s) will be lost")
            .setPositiveButton("Continue") { _, _ ->
                Pref.forceSingleTabMode = !Pref.forceSingleTabMode
                log(
                    "BrowserOptions : Single Tab mode",
                    if (Pref.forceSingleTabMode) "activated" else "deactivated",
                    "for page: " + browser.currentTab.webView?.url
                )
                browser.finish()
                browser.startActivity(browser.intent)
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun dismiss() = dismiss(browser.fragmentHost, browser)
    override fun dismiss(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        val options = view?.findViewById<View>(R.id.options_layout)
        if (options == null) {
            super.dismiss(browser.fragmentHost, browser); return
        }
        options.startAnimation(
            animFactory.menuContractAnim(options, 380.dp, 170.dp) {
                super.dismiss(browser.fragmentHost, browser)
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun setLoadingOption(
        currentTab: WebTab,
        parent: View,
        isLoading: Boolean = currentTab.isLoading
    ) {
        if (isLoading) {
            parent.findViewById<ImageView>(R.id.refreshIco).setImageResource(R.drawable.cross)
            parent.findViewById<View>(R.id.refreshIco)
                .setOnClickListener { cancelLoading(currentTab, parent) }
            currentTab.onLoadingFinished.add { setLoadingOption(currentTab, parent, false) }
        } else {
            parent.findViewById<ImageView>(R.id.refreshIco).setImageResource(R.drawable.refresh)
            parent.findViewById<View>(R.id.refreshIco)
                .setOnClickListener { reload(currentTab, parent) }
        }
    }

    private fun cancelLoading(currentTab: WebTab, parent: View) {
        log("BrowserOptions", "cancelLoading")
        dismiss()
        currentTab.cancel()
        setLoadingOption(currentTab, parent, false)
    }

    private fun reload(currentTab: WebTab, parent: View) {
        log("BrowserOptions", "reload page")
        dismiss()
        currentTab.reload()
        setLoadingOption(currentTab, parent, true)
    }

    private fun desktopMode(chkBx: CheckBox) {
        log("BrowserOptions", "desktopMode", browser.currentTab.address)
        dismiss()
        val currentTab = browser.currentTab
        currentTab.isDesktopMode = !currentTab.isDesktopMode
        chkBx.isChecked = currentTab.isDesktopMode
        with(browser.currentTab.webView ?: return) {
            if (currentTab.isDesktopMode) {
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.110 Safari/537.36"
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                reload()
            } else {
                settings.userAgentString = WebSettings.getDefaultUserAgent(browser)
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                reload()
            }
        }
    }

    private fun savePage() {
        log("BrowserOptions", "savePage")
        dismiss()
        fun save() {
            if (browser.currentTab.isLoading) {
                browser.showSnackBar("Can't save page while it is loading. Try again later")
            } else browser.currentTab.savePage()
        }

        fun showSaved() {
            val intent = Intent(browser, SavedPages::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            browser.startActivity(intent)
        }
        browser.dialog = AlertDialog.Builder(browser)
            .setTitle("Web page saving")
            .setMessage("Save the web page locally to view even when you are offline")
            .setPositiveButton("Save page") { d, _ -> save(); d.dismiss() }
            .setNegativeButton("View saved") { d, _ -> showSaved(); d.dismiss() }
            .setNeutralButton("Cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun settings() {
        log("BrowserOptions", "settings")
        dismiss()
        handler.postDelayed({
            SettingsActivity.start(browser, Browser::class.java.name)
        }, 120L)
    }

    private fun openInBrowser() {
        log("BrowserOptions", "openInBrowser")
        dismiss()
        handler.postDelayed({
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(browser.address.text.str.asSanitizedUrl())
            )
            try {
                browser.startActivity(browserIntent)
            } catch (e: Exception) {
                browser.showSnackBar(
                    "No installed browsers found.",
                    duration = Snackbar.LENGTH_LONG
                )
            }
        }, 120L)
    }

    private fun bookmarks(alreadyAdded: Boolean = false) {
        fun addToBM() {
            val webView = browser.currentTab.webView
            if (webView == null) {
                browser.showSnackBar("Some error occurred; Could not save bookmark")
                return
            }
            val title = webView.title ?: return
            val url = webView.url ?: return
            BookmarkItem(title, url, now()).apply {
                ico = webView.favicon?.toBase64()
                ioScope.launch { db.insert(this@apply) }
                browser.showSnackBar(
                    "Added $title to bookmarks",
                    duration = Snackbar.LENGTH_SHORT
                )
            }
        }

        fun removeFromBM() {
            val webView = browser.currentTab.webView
            if (webView == null) {
                browser.showSnackBar("Some error occurred; Could not save bookmark")
                return
            }
            val url = webView.url ?: return
            ioScope.launch { db.remove(url) }
            browser.showSnackBar(
                "Removed ${webView.title} from bookmarks",
                duration = Snackbar.LENGTH_SHORT
            )
        }

        fun showBMs() {
            val intent = Intent(browser, BookmarkActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            browser.startActivity(intent)
        }

        log("BrowserOptions", "bookmarks")
        dismiss()

        browser.dialog = AlertDialog.Builder(browser)
            .setTitle("Bookmarks")
            .setMessage(
                if (alreadyAdded) "Remove this page from bookmarks?"
                else "Add this page to your bookmarks?"
            )
            .setPositiveButton(if (alreadyAdded) "Remove" else "Add") { d, _ ->
                if (alreadyAdded) removeFromBM() else addToBM()
                d.dismiss()
            }
            .setNegativeButton("View all") { d, _ -> showBMs(); d.dismiss() }
            .setNeutralButton("Cancel") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }
    private fun history() {
        log("BrowserOptions", "history")
        dismiss()
        handler.postDelayed({
            val intent = Intent(browser, HistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            browser.startActivity(intent)
        }, 120L)
    }

    private fun scanQR() {
        log("BrowserOptions", "QrCodeScan")
        dismiss()
        handler.postDelayed({
            val intent = Intent(browser, QrCodeActivity::class.java)
            browser.startActivityForResult(intent, 8262)
        }, 120L)
    }
    private fun shareLink() {
        log("BrowserOptions", "shareLink")
        dismiss()
        handler.postDelayed({
            val sharingIntent = Intent(Intent.ACTION_SEND)
            sharingIntent.type = "text/plain"
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, "URL from HTTP-Downloader")
            sharingIntent.putExtra(Intent.EXTRA_TEXT, browser.address.text.str)
            browser.startActivity(Intent.createChooser(sharingIntent, "Share via"))
        }, 120L)
    }

    private fun exit() {
        log("BrowserOptions", "exit")
        browser.exitToSource()
    }

    private val Int.dp: Int
        get() {
            val metrics = browser.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}
