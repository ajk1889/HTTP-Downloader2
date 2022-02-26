package resonance.http.httpdownloader.fragments


import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.*
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.*

class MainMenu : OverlayFragmentParent() {
    private val mainActivity = MainActivity.instance
    private val animFactory = AnimFactory()
    private val handler = mainActivity.handler

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main_options, container, false).apply {
            findViewById<View>(R.id.overlay).setOnClickListener { dismiss() }
            findViewById<View>(R.id.advancedDownload).setOnClickListener { advancedDownload() }
            findViewById<View>(R.id.browser).setOnClickListener { startBrowser() }
            findViewById<View>(R.id.simpleMode).setOnClickListener { startSimpleMode() }
            findViewById<View>(R.id.fileJoiner).setOnClickListener { startFileJoiner() }
            findViewById<View>(R.id.fromShared).setOnClickListener { fromShared() }
            findViewById<View>(R.id.pauseAll).setOnClickListener { pauseAll() }
            findViewById<View>(R.id.settings).setOnClickListener { settings() }
            with(findViewById<View>(R.id.add_menu)) {
                startAnimation(animFactory.menuExpandAnim(this, 290.dp, 220.dp))
            }
        }
    }

    private fun pauseAll() {
        log("MainMenu", "pauseAll")
        dismiss()
        mainActivity.pauseAll()
    }

    private fun dismiss() = dismiss(mainActivity.fragmentHost, mainActivity)

    private fun settings() {
        log("MainMenu", "settings")
        dismiss()
        handler.postDelayed({
            SettingsActivity.start(mainActivity, MainActivity::class.java.name)
        }, 100)
    }

    private fun fromShared() {
        log("MainMenu", "fromShared")
        val view = mainActivity.layoutInflater.inflate(R.layout.dialog_edit_text_multiline, null)
        val editText = view.findViewById<EditText>(R.id.file_name)
        editText.gravity = Gravity.START or Gravity.TOP
        editText.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            150.dp
        ).apply { marginStart = 10.dp; marginEnd = 10.dp; topMargin = 10.dp }
        editText.hint = "Copy-paste data here"

        mainActivity.showKeyboard()
        dismiss()

        val dialog = AlertDialog.Builder(mainActivity)
            .setTitle("Download data")
            .setView(view)
            .setPositiveButton("Continue") { _, _ -> }
            .setNegativeButton("Cancel") { _, _ -> mainActivity.hideKeyboard() }
            .setNeutralButton("Scan QR") { _, _ ->
                mainActivity.hideKeyboard()
                handler.postDelayed({
                    val intent = Intent(mainActivity, QrCodeActivity::class.java)
                    mainActivity.startActivityForResult(intent, MainActivity.SHARED_DATA_QR)
                }, 300)
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val color = ContextCompat.getColor(mainActivity, R.color.colorAccent_dark)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            mainActivity.hideKeyboard()
            val text = editText.text.str.trim()
            if (text == "") {
                editText.setText("")
                editText.hint = "Valid download data required"
            } else {
                handler.postDelayed({
                    mainActivity.processSharedData(text)
                }, 300)
                dialog.dismiss()
            }
        }

        editText.postDelayed({
            if (mainActivity.pasteClipBoardTo(editText))
                dialog.setTitle("Data <i><font color='#3179A6'>(pasted)</font></i>".asHtml())
        }, 200)
    }

    private fun startFileJoiner() {
        log("MainMenu", "startFileJoiner")
        dismiss()
        handler.postDelayed({
            val intent = Intent(mainActivity, FileJoiner::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            mainActivity.startActivity(intent)
        }, 100)
    }

    private fun startSimpleMode() {
        log("MainMenu", "startSimpleMode")
        dismiss()
        handler.postDelayed({
            mainActivity.startActivity(
                Intent(mainActivity, SimpleActivity::class.java)
            )
        }, 100)
    }

    private fun startBrowser() {
        log("MainMenu", "startBrowser")
        dismiss()
        handler.postDelayed({
            Browser.start(
                context = mainActivity,
                from = Browser.Companion.FromOptions.MAIN,
                url = null,
                request = C.misc.startDownload
            )
        }, 100)
    }

    private fun advancedDownload() {
        log("MainMenu", "advancedDownload")
        dismiss()
        handler.postDelayed({
            mainActivity.startActivity(
                Intent(mainActivity, AdvancedDownload::class.java)
            )
        }, 100)
    }

    override fun dismiss(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        val menu = view?.findViewById<View>(R.id.add_menu)
        if (menu == null) {
            super.dismiss(mainActivity.fragmentHost, mainActivity); return
        }
        menu.startAnimation(
            animFactory.menuContractAnim(menu, 290.dp, 220.dp) {
                super.dismiss(mainActivity.fragmentHost, mainActivity)
                mainActivity.overlayFragment = null
            }
        )
    }

    private val Int.dp: Int
        get() {
            val metrics = mainActivity.resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}
