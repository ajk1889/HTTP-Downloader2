package resonance.http.httpdownloader.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.android.volley.Response
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.activity_browser.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.adapters.AutoCompleteAdapter
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.db.HistoryDB
import resonance.http.httpdownloader.helpers.db.HistoryDbConn
import resonance.http.httpdownloader.helpers.hideKeyboard
import resonance.http.httpdownloader.helpers.isValidUrl
import resonance.http.httpdownloader.helpers.showKeyboard

class SearchSuggestions : OverlayFragmentParent() {

    private val browser = Browser.instance
    private lateinit var autoCompleteAdapter: AutoCompleteAdapter
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val historyDB: HistoryDbConn by lazy {
        Room.databaseBuilder(
            browser.applicationContext,
            HistoryDB::class.java, "browserHistory"
        ).build().conn()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_search_suggestions, container, false).apply {
            val clearButton = findViewById<ImageView>(R.id.clearButton)
            val searchBox = findViewById<EditText>(R.id.searchText)

            findViewById<View>(R.id.parentView).setOnClickListener {
                dismiss(browser.fragmentHost, browser)
            }
            clearButton.setOnClickListener { searchBox.setText(""); autoCompleteAdapter.clear() }
            searchBox.addTextChangedListener(object : TextWatcher {
                var onGoingRequestExists = false

                //if this field is not null after completing a network request, another request is initiated to search this
                var lastRequest: String? = null
                var requestQueue = Volley.newRequestQueue(browser).apply {
                    addRequestFinishedListener<JsonArrayRequest> {
                        onGoingRequestExists = false
                        lastRequest?.also { updateSearchResult(it) }
                        lastRequest = null
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(p0: Editable?) {
                    val text = (p0 ?: return).str
                    if (text == "" || text.isValidUrl()) return
                    uiScope.launch {
                        autoCompleteAdapter.clear()
                        autoCompleteAdapter.addAll(searchInHistory(p0.str))
                        if (!onGoingRequestExists)
                            updateSearchResult(p0.str)
                        else lastRequest = p0.str
                    }
                }

                private fun updateSearchResult(query: String) {
                    onGoingRequestExists = true
                    if (query.isNotBlank()) {
                        requestQueue.add(
                            JsonArrayRequest(
                                "http://suggestqueries.google.com/complete/search?client=firefox&q="
                                        + Uri.encode(query),
                                Response.Listener {
                                    if (it.length() != 2) return@Listener
                                    val list = it.getJSONArray(1)
                                    for (i in 0 until list.length())
                                        autoCompleteAdapter.add(list.getString(i))
                                },
                                {}
                            )
                        )
                    } else autoCompleteAdapter.clear()
                }

                suspend fun searchInHistory(text: String) =
                    withContext(Dispatchers.IO) { historyDB.searchUrl("%$text%") }
            })
            searchBox.setOnEditorActionListener { v, _, _ ->
                browser.address.text = searchBox.text.toString()
                dismiss(browser.fragmentHost, browser)
                browser.currentTab.loadURL(searchBox.text.str)
                with(browser.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager) {
                    hideSoftInputFromWindow(v.windowToken, 0)
                }; true
            }
            val listView = findViewById<ListView>(R.id.list)
            autoCompleteAdapter = AutoCompleteAdapter(browser, searchBox)
            listView.adapter = autoCompleteAdapter
            autoCompleteAdapter.clear()
            listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, i, _ ->
                val item = autoCompleteAdapter.getItem(i)
                browser.address.text = searchBox.text.toString()
                dismiss(browser.fragmentHost, browser)
                if (item.isValidUrl()) browser.currentTab.loadURL(item!!)
                else browser.currentTab.loadURL("https://www.google.com/search?q=" + Uri.encode(item))
                with(browser.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager) {
                    hideSoftInputFromWindow(searchBox.windowToken, 0)
                }
            }

            // add text of addressBar in searchBox
            searchBox.setText(browser.address.text)
            searchBox.selectAll()

            searchBox.requestFocus()
            browser.showKeyboard()
        }
    }

    override fun dismiss(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        val options = view?.findViewById<View>(R.id.options_layout)
        if (options == null) {
            browser.hideKeyboard()
            super.dismiss(browser.fragmentHost, browser); return
        }
    }
}
