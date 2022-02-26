package resonance.http.httpdownloader.helpers

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_simple_mode.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.SimpleActivity
import resonance.http.httpdownloader.core.get
import resonance.http.httpdownloader.core.log

class Option(
    private val activity: SimpleActivity,
    private val option: String?,
    val short: String? = option,
    var onInit: ((Option) -> Unit)?,
    var onClick: ((Option) -> Unit)?,
    private var useCustomView: Boolean = false
) {
    private val defaultColor = ContextCompat.getColor(activity, R.color.colorAccent_light)
    var disabledReason = "Press back button to re-enable this option"
    var showDisabledUI = { activity.showSnackBar(disabledReason, duration = Snackbar.LENGTH_LONG) }
    lateinit var view: View
    private lateinit var textView: TextView

    var text = option
        set(value) {
            field = value
            if (initialized) textView.text = value?.asHtml()
        }
    var isEnabled = true
        set(value) {
            field = value
            if (initialized && (backgroundColor == defaultColor || backgroundColor == Color.DKGRAY)) {
                backgroundColor = if (value) defaultColor else Color.DKGRAY
            }
        }
    var backgroundColor: Int = defaultColor
        set(value) {
            field = value
            if (initialized) {
                val shape =
                    view.findViewById<HorizontalScrollView>(R.id.scrollable).background as GradientDrawable
                shape.setColor(value)
            }
        }

    private var initialized = false
    @SuppressLint("InflateParams")
    fun initialize(): Option {
        if (!useCustomView) {
            view = activity.layoutInflater.inflate(R.layout.options_text, null)
            textView = view.findViewById(R.id.text)
            textView.apply {
                text = option?.asHtml()
                background = background?.constantState?.newDrawable()?.mutate()
                setOnClickListener { onClickOption() }
            }
        }
        initialized = true
        if (!useCustomView) {
            // to directly change background color after initialized=true (using custom setter)
            if (backgroundColor == Color.DKGRAY || backgroundColor == defaultColor)
                backgroundColor = if (isEnabled) defaultColor else Color.DKGRAY
        }
        onInit?.invoke(this)
        return this
    }

    private fun onClickOption() {
        if (!isEnabled) {
            showDisabledUI()
            return
        }
        if (!activity.questionFactory.isForwardButtonClick) {
            //modifying reached questions
            val list = activity.questionFactory.reachedQuestions
            val name = activity.currentQuestion.name
            activity.questionFactory.reachedQuestions = list[0 to list.indexOf(name) + 1]
            activity.forward.hide()
        }
        short?.also { log("onClickOption", it) }
        onClick?.invoke(this)
    }
}
