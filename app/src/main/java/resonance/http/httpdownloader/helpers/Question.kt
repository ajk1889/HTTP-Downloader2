package resonance.http.httpdownloader.helpers

import android.annotation.SuppressLint
import android.widget.LinearLayout
import android.widget.TextView
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.SimpleActivity
import resonance.http.httpdownloader.core.get
import resonance.http.httpdownloader.core.silently

@SuppressLint("SetTextI18n", "InflateParams")
class Question(
    private val activity: SimpleActivity,
    val name: String,
    _description: String?,
    _question: String?,
    var onInit: ((Question) -> Unit)?,
    var onGoBack: (Question) -> Boolean,
    var onGoForward: ((Question) -> Boolean)? = null
) {
    private var options = mutableListOf<Option>()
    private var initialized = false
    lateinit var layout: LinearLayout

    var description = _description
        set(value) {
            if (initialized) updateDescription(value)
            field = value
        }
    var question = _question
        set(value) {
            if (initialized) updateQuestion(value)
            field = value
        }

    init {
        activity.currentQuestion = this
    }

    fun initialize(): Question {
        layout = activity.layoutInflater.inflate(R.layout.simple_item, null) as LinearLayout
        activity.questionFactory.reachedQuestions.apply {
            if (!contains(name)) add(name)
        }

        updateQuestion(question)
        updateDescription(description)

        for (op in options)
            layout.addView(op.initialize().view)

        initialized = true
        onInit?.invoke(this)
        return this
    }

    fun addTo(parent: LinearLayout) {
        parent.addView(layout)
    }

    fun addOption(option: Option) {
        if (!initialized) {
            options.add(option)
            return
        }
        options.add(option)
        layout.addView(option.initialize().view)
    }

    fun optionAt(index: Int): Option = options[index]

    fun onBackPressed(parent: LinearLayout) {
        if (onGoBack(this)) {
            layout.removeAllViews()
            parent.removeView(layout)
        }
    }

    fun removeAllBut(vararg indices: Int) {
        var i = 0
        while (i <= options.size - indices.size) {
            if (i !in indices) {
                silently { layout.removeViewAt(i + 2) }
                options.removeAt(i)
                indices.apply {
                    for (j in this.indices) indices[j] -= 1
                }
            } else i += 1
        }
    }

    private fun updateDescription(description: String?) {
        if (description == null)
            layout.findViewById<TextView>(R.id.descr).setGone()
        else {
            layout.findViewById<TextView>(R.id.descr).unHide()
            if (description.length > 100) {
                var shortDesc = description[0 to 100]
                //to avoid getting inside any open HTML tags
                if (shortDesc.lastIndexOf('<') > shortDesc.lastIndexOf('>'))
                    shortDesc = shortDesc[0 to shortDesc.lastIndexOf('<')]

                shortDesc += "... (<i><u><font color=\"blue\">view more</font></u></i>)"
                layout.findViewById<TextView>(R.id.descr).apply {
                    tag = true
                    this.text = shortDesc.asHtml()
                    this.setOnClickListener {
                        if (tag as Boolean)
                            this.text =
                                "$description (<i><u><font color=\"blue\">view less</font></u></i>)".asHtml()
                        else this.text = shortDesc.asHtml()
                        tag = !(tag as Boolean)
                    }
                }
            } else {
                layout.findViewById<TextView>(R.id.descr).text = description.asHtml()
            }
        }
    }

    private fun updateQuestion(question: String?) {
        if (question == null) layout.findViewById<TextView>(R.id.title).setGone()
        else {
            layout.findViewById<TextView>(R.id.title).unHide()
            layout.findViewById<TextView>(R.id.title).text = question.asHtml()
        }
    }

    override fun toString(): String {
        return "Question: $name"
    }
}