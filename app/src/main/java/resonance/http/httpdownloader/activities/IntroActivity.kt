package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.android.synthetic.main.activity_intro.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.*
import java.io.File


class IntroActivity : AppCompatActivity() {
    companion object {
        const val FOLDER_CHOOSE = 1287

        fun incompleteStep(ctx: Context): String? {
            if (isSdk29Plus()) {
                if (Pref.useInternal) {
                    return "Please choose a download folder"
                }
            } else if (!MainActivity.hasPermission(ctx)) {
                return "Permissions not granted"
            }
            return null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)
        if (isSdk29Plus()) permissionLayout.setGone()
        setCheckChangeListeners()
        setCheckStates()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (MainActivity.hasPermission(this))
            File(Environment.getExternalStorageDirectory(), "HTTP-Downloads").mkdirs()
        else permissionChk.isChecked = false
    }

    override fun onResume() {
        super.onResume()
        permissionChk.isChecked = MainActivity.hasPermission(this)
    }

    private fun setCheckStates() {
        permissionChk.isChecked = MainActivity.hasPermission(this)
    }

    private fun setCheckChangeListeners() {
        permissionChk.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!MainActivity.hasPermission(this))
                    ActivityCompat.requestPermissions(this, MainActivity.permissions, 250)
            } else if (MainActivity.hasPermission(this)) permissionChk.isChecked = true
        }
    }

    fun proceed(view: View) {
        val incompleteStep = incompleteStep(this)
        if (incompleteStep == null) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        } else showShortToast(incompleteStep)
    }

    fun requestPermissions(view: View) {
        if (MainActivity.hasPermission(this))
            permissionChk.isChecked = true
        else permissionChk.isChecked = !permissionChk.isChecked
    }

    @SuppressLint("SetTextI18n")
    private fun setFolderName(uriStr: String) {
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            folderName.text = "<font color=red>Error</font>".asHtml()
            return
        }
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return
        if (folder.isFile || folder.name == null) return
        folderName.text = "${folder.name}/"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FOLDER_CHOOSE) {
            if (resultCode == Activity.RESULT_OK)
                data?.data?.also {
                    contentResolver.takePersistableUriPermission(it, data.getRWFlags())
                    setFolderName(it.str)
                    Pref.downloadLocation = it.str
                    Pref.useInternal = false
                } ?: showShortToast("Invalid output folder")
            else showShortToast("No output folder selected")
        }
    }

    fun changeDownloadFolder(view: View) {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                FOLDER_CHOOSE
            )
        } catch (e: Exception) {
            showShortToast("Can't launch file picker")
        }
    }
}