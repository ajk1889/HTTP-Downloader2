package resonance.http.httpdownloader.adapters

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_browser.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.getClipboardText
import resonance.http.httpdownloader.helpers.isValidUrl

class AutoCompleteAdapter(private val browser: Browser, private val searchBox: EditText) :
    ArrayAdapter<String>(browser, R.layout.autocomplete_item_layout) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view =
            convertView ?: browser.layoutInflater.inflate(R.layout.autocomplete_item_layout, null)
        val suggestion = getItem(position) ?: return view
        val searchQuery = searchBox.text.str
        view.findViewById<TextView>(R.id.resultText).text =
            if (suggestion.startsWith(searchQuery)) suggestion.trimFor(searchQuery)
            else suggestion
        if (suggestion.isValidUrl())
            view.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.browser_icon)
        else
            view.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.search)
        view.findViewById<ImageView>(R.id.fill_search)
            .setOnClickListener { searchBox.setText(suggestion); searchBox.setSelection(suggestion.length) }
        return view.apply { requestLayout() }
    }

    private fun String.trimFor(subString: String): String {
        return if (this.contains(subString)) {
            val show = this.substring(subString.length)
            if (subString.contains(" "))
                "... " + subString.substring(subString.lastIndexOf(" ") + 1) + show
            else subString + show
        } else this
    }

    override fun clear() {
        super.clear()
        val clip = browser.getClipboardText()
        if (browser.address.text != clip && clip.isValidUrl()) add(clip)
    }
}