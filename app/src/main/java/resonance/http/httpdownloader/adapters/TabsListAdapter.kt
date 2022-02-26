package resonance.http.httpdownloader.adapters

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.fragments.WebTab
import resonance.http.httpdownloader.helpers.AnimFactory
import resonance.http.httpdownloader.helpers.hide
import resonance.http.httpdownloader.helpers.unHide
import kotlin.math.min

class TabsAdapter(private val browser: Browser) :
    ArrayAdapter<Tab>(browser, R.layout.tabs_list_item) {
    lateinit var tabsList: View
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: browser.layoutInflater.inflate(R.layout.tabs_list_item, null)
        val tab = getItem(position) ?: throw NullPointerException(
            "Null object at index=$position, " +
                    "adapter-length=$count in TabsAdapter.getView"
        )

        if (tab.webTab == browser.currentTab)
            view.setBackgroundResource(R.drawable.round_ddd)
        else view.setBackgroundColor(Color.WHITE)

        tab.webTab.getTitle(24, false).also {
            view.findViewById<TextView>(R.id.title).text = it
        }

        if (tab.webTab.isLoading) {
            view.findViewById<ImageView>(R.id.ico).hide()
            view.findViewById<ProgressBar>(R.id.loading).unHide()
            tab.webTab.onIconReceived = {
                addAll(
                    MutableList(count) { getItem(it)!! }.also {
                        this.clear() /*clear adapter not list*/
                    }
                )
            }
        } else {
            view.findViewById<ImageView>(R.id.ico).unHide()
            with(tab.webTab.webView?.favicon) {
                if (this != null) view.findViewById<ImageView>(R.id.ico).setImageBitmap(this)
                else view.findViewById<ImageView>(R.id.ico).setImageResource(R.drawable.browser_icon)
            }
            view.findViewById<ProgressBar>(R.id.loading).hide()
        }
        view.findViewById<ImageView>(R.id.close).setOnClickListener {
            view.startAnimation(
                AnimFactory().listRemoveAnim(view, 40.dp) {
                    browser.webTabAdapter.removeTab(Browser.tabsAdapter.count - position - 1)
                    tabsList.layoutParams = tabsList.layoutParams.apply {
                        height = min((47 + count * 34), 235).dp
                    }
                }
            )
        }
        return view.also { it.requestLayout() }
    }

    private val Int.dp: Int
        get() {
            val metrics = browser.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}

val initiate get() = "aW5pdGlhdGU="
class Tab(var webTab: WebTab) {
    var webTabView: FrameLayout? = null
    override fun toString(): String {
        return "Tab host for $webTab"
    }
}