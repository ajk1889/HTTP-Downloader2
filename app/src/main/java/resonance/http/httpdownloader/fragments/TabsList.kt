package resonance.http.httpdownloader.fragments


import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_browser.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.helpers.AnimFactory
import resonance.http.httpdownloader.helpers.Pref
import kotlin.math.min

class TabsList : OverlayFragmentParent() {

    private val browser get() = Browser.instance
    private val tabsAdapter get() = Browser.tabsAdapter
    private val animFactory = AnimFactory()
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_tabs_list, container, false).apply {
            findViewById<RelativeLayout>(R.id.overlay).setOnClickListener {
                dismiss(browser.fragmentHost, browser)
            }
            findViewById<ImageView>(R.id.addNewTabButton).setOnClickListener {
                browser.webTabAdapter.addNewTab(Pref.browserHome)
                browser.address.text = Pref.browserHome
                browser.address.post {
                    if (browser.overlayFragment is SearchSuggestions)
                        browser.overlayFragment?.dismiss(browser.fragmentHost, browser)
                }
                dismiss(browser.fragmentHost, browser)
                browser.webTabAdapter.setCurrentItem(browser.webTabAdapter.count - 1)
            }
            findViewById<ListView>(R.id.tabsList).also {
                it.adapter = tabsAdapter
                tabsAdapter.tabsList = findViewById(R.id.tabsListLayout)
                it.setOnItemClickListener { _, _, index, _ ->
                    // since tabs order is reversed while displaying
                    browser.webTabAdapter.setCurrentItem(tabsAdapter.count - index - 1)
                    dismiss(browser.fragmentHost, browser)
                    with(tabsAdapter.getItem(index) ?: return@setOnItemClickListener) {
                        val address = webTab.webView?.url ?: ""
                        browser.address.text = address
                        browser.address.post {
                            if (browser.overlayFragment is SearchSuggestions)
                                browser.overlayFragment?.dismiss(browser.fragmentHost, browser)
                        }
                    }
                }
            }
            with(findViewById<View>(R.id.tabsListLayout)) {
                val height = min((47 + tabsAdapter.count * 34), 235)
                this.startAnimation(
                    animFactory.menuExpandAnim(this, height.dp, 250.dp)
                )
            }
        }
    }

    override fun dismiss(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        val tabsList = view?.findViewById<View>(R.id.tabsListLayout)
        if (tabsList == null) {
            super.dismiss(browser.fragmentHost, browser); return
        }
        val height = min((47 + tabsAdapter.count * 34), 235)
        tabsList.startAnimation(
            animFactory.menuContractAnim(tabsList, height.dp, 250.dp) {
                super.dismiss(browser.fragmentHost, browser)
            }
        )
    }

    private val Int.dp: Int
        get() {
            val metrics = browser.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}
