package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.helpers.asHtml

@SuppressLint("Registered")
open class ParentActivity : AppCompatActivity() {
    @Suppress("PrivatePropertyName")
    val handler = Handler(Looper.getMainLooper())

    enum class Status { LOADING, LOADED, FAILED }

    fun dismissSnackBar() {
        snackBar?.dismiss(); snackBar = null
        onSnackBarDismiss?.invoke()
        onSnackBarDismiss = null
    }

    var snackBar: Snackbar? = null
    var onSnackBarDismiss: (() -> Unit)? = null
    fun showSnackBar(
        msg: String,
        btnTxt: String = "OK",
        duration: Int = Snackbar.LENGTH_INDEFINITE,
        onDismiss: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ) {
        log("SimpleActivity", "showSnackBar: ", msg, btnTxt, duration, onDismiss, onClick)
        onSnackBarDismiss = onDismiss
        Snackbar.make(findViewById(R.id.parentView), msg.asHtml(), duration).also { snackBar ->
            val tv: TextView = snackBar.view.findViewById(R.id.snackbar_text)
            tv.maxLines = 6
            var clicked = false
            if (duration != Snackbar.LENGTH_INDEFINITE) {
                handler.postDelayed(
                    {
                        this.snackBar = null
                        if (!clicked) onSnackBarDismiss?.invoke()
                        onSnackBarDismiss = null
                    },
                    if (duration == Snackbar.LENGTH_SHORT) 2000L else 3500L
                )
            }
            snackBar.setAction(btnTxt) {
                clicked = true
                onClick?.invoke()
                this.snackBar?.also { it.dismiss() }
                this.snackBar = null
            }
            this.snackBar?.also {
                it.dismiss()
                onSnackBarDismiss?.invoke()
                onSnackBarDismiss = null
            }
            this.snackBar = snackBar; snackBar.show()
        }
    }

    val Int.dp: Int
        get() {
            val metrics = resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), metrics)
                .toInt()
        }
}