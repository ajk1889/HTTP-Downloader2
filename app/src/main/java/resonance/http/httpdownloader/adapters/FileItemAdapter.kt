package resonance.http.httpdownloader.adapters

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_file_joiner.*
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.FileJoiner
import resonance.http.httpdownloader.core.formatSize
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.shorten
import resonance.http.httpdownloader.core.str
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.implementations.UriFile

class FileItemAdapter(
    private val activity: FileJoiner,
    private val items: MutableList<UriFile>
) : ArrayAdapter<UriFile>(activity.applicationContext, R.layout.download_list_main) {

    private val files = activity.files
    private val anim = AnimFactory(150)
    var isEnabled = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: View.inflate(activity, R.layout.file_item, null)
        val item = getItem(position)
        setViewProperties(view, position, item)
        setClickListeners(view, position)
        view.requestLayout()
        return view
    }

    private fun setClickListeners(view: View, position: Int) {
        val file = files[position]
        view.findViewById<ImageView>(R.id.delete).setOnClickListener {
            if (!isEnabled) {
                activity.showSnackBar("You can't modify parts while joining is in progress")
                return@setOnClickListener
            }
            AlertDialog.Builder(activity)
                .setTitle("Remove item?")
                .setMessage(
                    """Are you sure to remove <b>${file.name}</b> from the list?<br/>
                        |<b><u>Tip:</b></u> You must add all files in correct order before joining
                    """.trimMargin().asHtml()
                )
                .setPositiveButton("Remove") { d, _ ->
                    log("FileItemAdapter", "remove item clicked: position=$position")
                    d.dismiss()
                    if (isEnabled) {
                        val wrapper = view.findViewById<View>(R.id.wrapper)
                        wrapper.measure(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        wrapper.startAnimation(
                            anim.listRemoveAnim(wrapper, wrapper.measuredHeight) { remove(file) }
                        )
                    } else activity.showSnackBar("You can't modify parts while joining is in progress")
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .show()
        }
        view.findViewById<ImageView>(R.id.downButton).setOnClickListener {
            if (!isEnabled) {
                activity.showSnackBar("You can't modify parts while joining is in progress")
                return@setOnClickListener
            }
            log("FileItemAdapter", "down button clicked: position=$position")
            if (position + 1 >= files.size) {
                view.findViewById<ImageView>(R.id.downButton).hide()
                return@setOnClickListener
            }
            view.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.move_down).apply {
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationRepeat(animation: Animation?) {}
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        val downFile = files[position + 1]
                        files[position + 1] = files[position]
                        files[position] = downFile
                        notifyDataSetChanged()
                    }
                })
            })
            activity.list.apply {
                getChildAt(position - firstVisiblePosition + 1)?.startAnimation(
                    AnimationUtils.loadAnimation(activity, R.anim.move_up)
                )
            }
        }
        view.findViewById<ImageView>(R.id.upButton).setOnClickListener {
            if (!isEnabled) {
                activity.showSnackBar("You can't modify parts while joining is in progress")
                return@setOnClickListener
            }
            log("FileItemAdapter", "up button clicked: position=$position")
            if (position <= 0) {
                view.findViewById<ImageView>(R.id.upButton).hide()
                return@setOnClickListener
            }
            view.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.move_up).apply {
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationRepeat(animation: Animation?) {}
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        val upFile = files[position - 1]
                        files[position - 1] = files[position]
                        files[position] = upFile
                        notifyDataSetChanged()
                    }
                })
            })
            activity.list.apply {
                getChildAt(position - firstVisiblePosition - 1)?.startAnimation(
                    AnimationUtils.loadAnimation(activity, R.anim.move_down)
                )
            }
        }
    }

    private fun setViewProperties(view: View, position: Int, item: UriFile) {
        view.findViewById<TextView>(R.id.index).text = (position + 1).str
        view.findViewById<TextView>(R.id.name).text = item.name.shorten(33)
        view.findViewById<TextView>(R.id.size).text = formatSize(item.size, 3, " ")
        view.findViewById<View>(R.id.wrapper).layoutParams.apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        if (position == 0)
            view.findViewById<ImageView>(R.id.upButton).hide()
        else view.findViewById<ImageView>(R.id.upButton).unHide()

        if (position == count - 1)
            view.findViewById<ImageView>(R.id.downButton).hide()
        else view.findViewById<ImageView>(R.id.downButton).unHide()
    }

    override fun notifyDataSetChanged() {
        updateContinueBtns()
        updateNothingIndicator()
        updateAppendTo1stFileOption()
        activity.updateStorageInfo()
        super.notifyDataSetChanged()
    }

    @SuppressLint("SetTextI18n")
    private fun updateAppendTo1stFileOption() {
        if (files.size < 2) {
            activity.appendToFirst.isChecked = false
            activity.selectOpFile.enable()
            activity.startJoinBtn.disable()
        } else if (activity.appendToFirst.isChecked) {
            activity.selectOpFile.disable()
            activity.startJoinBtn.enable()
            activity.opName.text = "Output file name: <b>${files[0].name}</b>".asHtml()
        }
    }

    private fun updateContinueBtns() {
        if (files.size > 1) {
            activity.continueBtn1.enable()
            if (activity.outputFile != null || activity.appendToFirst.isChecked)
                activity.startJoinBtn.enable()
            else activity.startJoinBtn.disable()
        } else {
            activity.continueBtn1.disable()
            activity.startJoinBtn.disable()
        }
    }

    private fun updateNothingIndicator() {
        if (files.size > 0)
            activity.nothingIndicator.hide()
        else activity.nothingIndicator.unHide()
    }

    override fun getItem(position: Int): UriFile {
        return files[position]
    }

    override fun add(`object`: UriFile?) {
        if (`object` == null) return
        files.add(`object`)
        notifyDataSetChanged()
    }

    override fun addAll(collection: MutableCollection<out UriFile>) {
        files.addAll(collection)
        notifyDataSetChanged()
    }

    override fun addAll(vararg items: UriFile) {
        files.addAll(items)
        notifyDataSetChanged()
    }

    override fun remove(item: UriFile?) {
        if (item == null) return
        files.remove(item)
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
}