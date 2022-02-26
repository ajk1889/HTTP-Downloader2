package resonance.http.httpdownloader.adapters

import android.annotation.SuppressLint
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_history_bookmark.*
import kotlinx.android.synthetic.main.web_link_item.view.*
import kotlinx.coroutines.launch
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.BookmarkActivity
import resonance.http.httpdownloader.activities.BookmarkActivity.Past.*
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.helpers.db.BookmarkItem

class BookmarkAdapter(private val activity: BookmarkActivity) :
    ArrayAdapter<BookmarkItem>(activity, R.layout.web_link_item) {
    private val listView = activity.historyList
    private val anim = AnimFactory()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return super.getView(position, convertView, parent)
        val past = item.time.toPast()
        return if (past != null)
            buildTimeIndicator(position, item, past).apply { tag = item }
        else buildBookmarkItemView(item).apply { tag = item }
    }

    @SuppressLint("InflateParams")
    private fun buildTimeIndicator(
        position: Int,
        item: BookmarkItem,
        past: BookmarkActivity.Past
    ): View {
        return activity.layoutInflater.inflate(R.layout.time_indicator, null).apply {
            findViewById<TextView>(R.id.text).text = past.text

            val checkBox = findViewById<CheckBox>(R.id.checkbox)
            if (activity.isLongClicked) {
                this.setOnClickListener { checkBox.isChecked = !checkbox.isChecked }
                checkBox.apply { unHide(); if (item.isSelected) setCheckedBlindly(true) }
            } else this.setOnClickListener { } // to avoid showing clicking animation

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                for (i in listView.indexOfChild(this) + 1 until listView.count) {
                    val child = listView.getChildAt(i) ?: break
                    if (child.findViewById<View>(R.id.text) == null)
                        child.findViewById<CheckBox>(R.id.checkbox)?.setCheckedBlindly(isChecked)
                    else break //next past zone reached
                }
                for (i in position + 1 until count) {
                    val belowItem = getItem(i) ?: break
                    if (belowItem in past) belowItem.isSelected = isChecked
                    else break
                }
            }
            requestLayout()
        }
    }

    @SuppressLint("InflateParams")
    private fun buildBookmarkItemView(item: BookmarkItem): View {
        return activity.layoutInflater.inflate(R.layout.web_link_item, null).apply {
            findViewById<TextView>(R.id.title).text =
                if (item.title.isEmpty()) "No title" else item.title
            item.ico?.toBitmap().also {
                if (it == null)
                    findViewById<ImageView>(R.id.ico).setImageResource(R.drawable.browser_icon)
                else findViewById<ImageView>(R.id.ico).setImageBitmap(it)
            }
            findViewById<TextView>(R.id.url).text = item.url
            findViewById<ImageView>(R.id.delete).setOnClickListener {
                this.startAnimation(anim.listRemoveAnim(this, this.height) {
                    this@BookmarkAdapter.remove(item)
                })
                activity.ioScope.launch { activity.db.delete(item) }
            }
            val checkBox = findViewById<CheckBox>(R.id.checkbox)
            if (activity.isLongClicked) {
                findViewById<ImageView>(R.id.delete).setGone()
                checkBox.apply {
                    unHide(); if (item.isSelected) setCheckedBlindly(true)
                }
            }
            setOnClickListener {
                if (activity.isLongClicked) {
                    checkBox.apply { isChecked = !isChecked }
                } else {
                    Browser.start(context = activity, from = null, url = item.url)
                    activity.finish()
                }
            }
            setOnLongClickListener {
                if (!activity.isLongClicked) {
                    activateLongClickMode()
                    checkBox.isChecked = true
                } else {
                    deactivateLongClickMode()
                    with(activity) {
                        if (copyToClipBoard(item.url))
                            showShortToast("Copied url to clipboard")
                        else showShortToast("Failed to copy")
                    }
                }
                true
            }
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                checkTimeIndicatorIfNeeded()
            }
            requestLayout()
        }
    }

    @Volatile
    var shouldCheck = true

    private fun checkTimeIndicatorIfNeeded() {
        if (!shouldCheck) return
        var timeIndicator = getItem(0) ?: return
        var isAllSelected = true
        for (i in 1 until count) {
            val item = getItem(i) ?: continue
            if (!item.isTimeIndicator) { // normal bookmarkItem item
                isAllSelected = isAllSelected && item.isSelected
            } else {
                if (isAllSelected != timeIndicator.isSelected) {
                    timeIndicator.isSelected = isAllSelected
                    notifyDataSetChanged()
                }
                isAllSelected = true
                timeIndicator = item
            }
        }
        if (isAllSelected != timeIndicator.isSelected) {
            timeIndicator.isSelected = isAllSelected
            notifyDataSetChanged()
        }
    }

    @Synchronized
    fun CheckBox.setCheckedBlindly(checked: Boolean) {
        shouldCheck = false
        isChecked = checked
        shouldCheck = true
    }

    fun deactivateLongClickMode() {
        activity.isLongClicked = false
        activity.searchBtn.setOnClickListener { }
        anim.animateBtn(activity.searchBtn, R.drawable.search) {
            activity.searchBtn.setOnClickListener { activity.searchToggle(it) }
        }
        repeat(count) { getItem(it)?.isSelected = false }
        repeat(listView.childCount) {
            listView.getChildAt(it)?.apply {
                findViewById<CheckBox>(R.id.checkbox)?.apply {
                    setCheckedBlindly(false); setGone()
                }
                findViewById<ImageView>(R.id.delete)?.unHide()

                // past showing view (to disable touch effects)
                (findViewById<TextView>(R.id.text)?.parent as View?)?.setOnClickListener {}
            }
        }
    }

    private fun activateLongClickMode() {
        activity.isLongClicked = true
        activity.searchBtn.setOnClickListener { }
        anim.animateBtn(activity.searchBtn, R.drawable.delete) {
            activity.searchBtn.setOnClickListener { deleteSelected() }
        }

        repeat(listView.childCount) {
            listView.getChildAt(it)?.apply {
                findViewById<CheckBox>(R.id.checkbox)?.apply {
                    setCheckedBlindly(false); unHide()
                }
                findViewById<ImageView>(R.id.delete)?.setGone()

                // past showing view (to change checkbox state when clicked)
                (findViewById<TextView>(R.id.text)?.parent as View?)?.setOnClickListener {
                    findViewById<CheckBox>(R.id.checkbox).apply { isChecked = !isChecked }
                }
            }
        }
    }

    private fun deleteSelected() {
        val toRemove = mutableListOf<BookmarkItem>()
        repeat(count) {
            val item = getItem(it)
            if (item != null && item.isSelected)
                toRemove.add(item)
        }
        toRemove.forEach {
            val view = it.viewInList
            view?.startAnimation(
                anim.listRemoveAnim(view, view.height) { this.remove(it) }
            ) ?: this.remove(it)
        }
        Handler().postDelayed({
            deactivateLongClickMode()
            if (this.isEmpty) activity.emptyIndicator.unHide()
        }, 300)
        activity.ioScope.launch {
            activity.db.removeAll(Array(toRemove.size) { toRemove[it].id })
        }
    }

    private val BookmarkItem.viewInList: View?
        get() {
            repeat(activity.historyList.count) {
                val view: View? = activity.historyList.getChildAt(it)
                if (view?.tag == this)
                    return view
            }
            return null
        }

    private fun Long.toPast(): BookmarkActivity.Past? {
        return when (this) {
            PAST_12_HOURS.time -> PAST_12_HOURS
            PAST_24_HOURS.time -> PAST_24_HOURS
            PAST_2_DAYS.time -> PAST_2_DAYS
            PAST_7_DAYS.time -> PAST_7_DAYS
            PAST_30_DAYS.time -> PAST_30_DAYS
            OLDER.time -> OLDER
            else -> null
        }
    }

    private val BookmarkItem.isTimeIndicator
        get() = time < PAST_30_DAYS.time
}