package resonance.http.httpdownloader.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView
import resonance.http.httpdownloader.helpers.C

class QrCodeActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {
    lateinit var view: ZXingScannerView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = ZXingScannerView(this)
        setContentView(view)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 328)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 328) {
            if (grantResults.isEmpty() || grantResults[0] != 0) finish()
            else view.startCamera()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        view.setResultHandler(this)
        view.startCamera()
        super.onResume()
    }

    override fun onPause() {
        view.stopCamera()
        super.onPause()
    }

    override fun handleResult(p0: Result?) {
        Intent().apply {
            putExtra(C.misc.qrScanResult, p0?.text)
            setResult(Activity.RESULT_OK, this)
            finish()
        }
    }
}
