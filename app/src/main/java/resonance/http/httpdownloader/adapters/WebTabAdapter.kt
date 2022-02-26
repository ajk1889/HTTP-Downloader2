package resonance.http.httpdownloader.adapters

import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.android.synthetic.main.activity_browser.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.fragments.WebTab
import resonance.http.httpdownloader.helpers.*

class WebTabAdapter(
    private val browser: Browser,
    private val tabsAdapter: TabsAdapter
) {
    companion object {
        const val NO_TABS_ADDED = -1
        const val CURRENT_TAB_REMOVED = -2
    }

    private val tabs = mutableListOf<Tab>()
    lateinit var currentTab: WebTab
    val count get() = tabs.size
    var current: Int = NO_TABS_ADDED
    var onDownloadIntercept = C.misc.startDownload

    fun setCurrentItem(new: Int) {
        val old = current
        val newTab = tabs[new]
        if (old == NO_TABS_ADDED) { //while adding 1st tab to browser
            newTab.webTabView = browser.findViewById(R.id.webTabView)
            browser.supportFragmentManager.beginTransaction()
                .add(R.id.webTabView, newTab.webTab)
                .commit()
        } else if (old == CURRENT_TAB_REMOVED) {
            if (newTab.webTabView == null) { //when the tab is newly added
                newTab.webTab = WebTab.newInstance(new, Pref.browserHome).apply {
                    onDownloadIntercept = this@WebTabAdapter.onDownloadIntercept
                }
                newTab.webTabView = replaceWithNewView(null)
                browser.supportFragmentManager.beginTransaction()
                    .add(R.id.webTabView, newTab.webTab)
                    .commit()
            } else  //happens when the tab already exists & user is switching between tabs
                switchViews(null, newTab.webTabView!!)
        } else {
            val oldTab = tabs[old]
            if (newTab.webTabView == null) { //when the tab is newly added
                newTab.webTabView = replaceWithNewView(oldTab.webTabView!!)
                browser.supportFragmentManager.beginTransaction()
                    .add(R.id.webTabView, newTab.webTab)
                    .commit()
            } else  //happens when the tab already exists & user is switching between tabs
                switchViews(oldTab.webTabView!!, newTab.webTabView!!)
        }
        current = new
        currentTab = newTab.webTab
    }

    private val animFactory = AnimFactory()
    private fun switchViews(old: FrameLayout?, new: FrameLayout) {
        animFactory.animateTabReplacement(browser, old, new)
        if (old != null) old.id = new.id
        new.id = R.id.webTabView
    }

    private fun replaceWithNewView(old: FrameLayout?): FrameLayout {
        val new = FrameLayout(browser)
        browser.tabsParent.addView(new)
        new.layoutParams = new.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        old?.setGone(); new.unHide()
        if (old != null) old.id = new.id
        new.id = R.id.webTabView
        return new
    }

    fun addNewTab(url: String?, referer: String? = null) {
        val newTab = Tab(
            WebTab.newInstance(tabs.size, url, referer).apply {
                onDownloadIntercept = this@WebTabAdapter.onDownloadIntercept
            }
        )
        tabs.add(newTab)
        tabsAdapter.insert(newTab, 0)
        browser.tabsCount.text = tabs.size.str
        setCurrentItem(count - 1)
        browser.address.text = url ?: ""
    }

    fun removeTab(index: Int) {
        browser.tabsParent.removeViewAt(index)
        tabsAdapter.remove(tabs[index])
        tabs.removeAt(index).webTab.webView?.destroy()

        //Adjusting position after an item removal
        for (i in tabs.indices) tabs[i].webTab.position = i

        browser.tabsCount.text = tabs.size.str

        if (index == current) {
            browser.overlayFragment?.dismiss(browser.fragmentHost, browser)
            when {
                tabs.size == 0 -> {
                    current = CURRENT_TAB_REMOVED; addNewTab(Pref.browserHome)
                }
                //as 0th tab is removed, previous 1st tab will occupy 0th position now.
                index == tabs.size -> {
                    current = CURRENT_TAB_REMOVED; setCurrentItem(tabs.lastIndex)
                }
                //showing previous tab
                else -> {
                    val old = current
                    current = CURRENT_TAB_REMOVED

                    if (old == 0) setCurrentItem(0)
                    else setCurrentItem(old - 1)
                }
            }
        } else if (index < current) current--
        currentTab.webView?.url?.also { currentTab.address = it }
    }

    fun forAll(fn: (tab: WebTab) -> Unit) {
        for (tab in tabs) fn(tab.webTab)
    }

    fun indexOf(url: String): Int {
        for (i in tabs.indices)
            if (tabs[i].webTab.webView?.url == url) return i
        return -1
    }
}