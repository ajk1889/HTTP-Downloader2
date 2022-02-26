package resonance.http.httpdownloader.activities

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.android.synthetic.main.activity_simple_mode.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.helpers.*

class SimpleActivity : ParentActivity() {
    lateinit var transferServiceConnection: TransferServiceConnection
    lateinit var questionFactory: QuestionFactory
    var requestUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_mode)
        log("SimpleActivity", "oCr:")

        if (intent?.type == "text/plain") showSharedUrlOptions()

        Pref.lastBrowserDownloadData = null

        transferServiceConnection = TransferServiceConnection(this)
        questions_container.layoutTransition.addTransitionListener(object :
            LayoutTransition.TransitionListener {
            override fun startTransition(a: LayoutTransition?, b: ViewGroup?, c: View?, d: Int) {
            }

            override fun endTransition(a: LayoutTransition?, b: ViewGroup?, c: View?, d: Int) {
                scrollToBottom()
            }
        })
        questionFactory = QuestionFactory(this)
        questionFactory.question0().initialize().addTo(questions_container)
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showSharedUrlOptions() {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val view = layoutInflater.inflate(R.layout.dialog_edit_text_single_line, null)
        val editText = view.findViewById<EditText>(R.id.editText)
        editText.setText(text)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit URL")
            .setView(view)
            .setPositiveButton("Continue") { _, _ -> }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); finish() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawText = editText.text.toString()
            var url = rawText.trim()
            if (!(url.startsWith("http://") || url.startsWith("https://")))
                url = "http://$url"
            if (url.isValidUrl() && url.contains(".")) {
                dialog.dismiss()
                requestUrl = url
                showSnackBar("Browser will open this page when launched")
            } else {
                editText.setTextColor(Color.parseColor("#ff5555"))
                editText.setText("Invalid URL")
                handler.postDelayed({
                    editText.setTextColor(Color.BLACK)
                    editText.setText(rawText)
                }, 500)
            }
        }
    }

    val onResumeTriggers = mutableMapOf<String, () -> Any?>()
    override fun onResume() {
        super.onResume()

        val countBefore = questions_container.childCount
        for (v in onResumeTriggers.values) v()
        val countAfter = questions_container.childCount

        if (countBefore != countAfter) //some views are added; should scroll to bottom
            scrollToBottom()
    }

    val onActivityResultTriggers =
        mutableMapOf<String, (requestCode: Int, resultCode: Int, data: Intent?) -> Any?>()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val countBefore = questions_container.childCount
        for (v in onActivityResultTriggers.values)
            v.invoke(requestCode, resultCode, data)
        val countAfter = questions_container.childCount

        if (countBefore != countAfter) //some views are added; should scroll to bottom
            scrollToBottom()
    }

    internal fun scrollToBottom() {
        scroller.post {
            scroller.apply {
                val lastChild = getChildAt(0)
                val bottom = lastChild.bottom + paddingBottom
                val delta = bottom - (scrollY + height)
                smoothScrollBy(0, delta)
            }
        }
    }

    lateinit var currentQuestion: Question
    var lastBackPressedTime = 0L
    override fun onBackPressed() {
        if (snackBar != null) {
            dismissSnackBar()
            return
        }
        val now = now()
        log(
            "SimpleActivity",
            "onBackPressed: sending to current Question",
            currentQuestion.name,
            currentQuestion.question?.run {
                if (length < 25) this else substring(0, 25) + "..."
            }
        )
        lastBackPressedTime = now
        currentQuestion.onBackPressed(questions_container)
        forward.unHide()
    }

    fun forward(view: View) {
        //currentQuestion.onGoForward returns false when it couldn't go forward
        log("SimpleActivity", "forward: button clicked")
        if (currentQuestion.onGoForward?.invoke(currentQuestion) != true)
            forward.hide()
    }

    fun home(view: View) {
        log("SimpleActivity", "home: button (clear all) clicked; prompting..")
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage("""Are you sure to clear all and go to home page?<br>
                |<b>This action cannot be undone</b>""".trimMargin().asHtml())
            .setCancelable(true)
            .setPositiveButton("Clear") { d, _ ->
                log("SimpleActivity", "home: clear all selected")
                questions_container.removeAllViews()
                questionFactory = QuestionFactory(this)
                questionFactory.question0().initialize().addTo(questions_container)
                forward.hide()
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    fun exit(view: View) {
        log("SimpleActivity", "exit: button clicked")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}