package resonance.http.httpdownloader.fragments


import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.helpers.hide
import resonance.http.httpdownloader.helpers.unHide

open class OverlayFragmentParent : Fragment() {
    var isShowing = false
    open fun dismiss(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        isShowing = false
        fragmentHost.hide()
        MainActivity.changeStatusBarColor(activity, "#ffffff")
        with(activity.supportFragmentManager) {
            val fragment = findFragmentByTag("options") ?: return
            beginTransaction()
                .remove(fragment)
                .runOnCommit { MainActivity.changeStatusBarColor(activity, "#ffffff") }
                .commitAllowingStateLoss()
        }
    }

    fun show(fragmentHost: FrameLayout, activity: AppCompatActivity) {
        isShowing = true
        fragmentHost.unHide()
        activity.supportFragmentManager
            .beginTransaction()
            .add(R.id.fragmentHost, this, "options")
            .runOnCommit { MainActivity.changeStatusBarColor(activity, "#20000000") }
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {}
}
