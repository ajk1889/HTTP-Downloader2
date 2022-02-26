package resonance.http.httpdownloader.views

import android.annotation.SuppressLint
import android.os.Build
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AlertDialog
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.core.hours
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.helpers.asHtml
import java.util.*

class TimePickerDialog(
    private val activity: MainActivity,
    initTime: Long = System.currentTimeMillis() + 24.hours,
    private val onTimeSelected: (Long) -> Unit,
    private val onScheduleRemoved: () -> Unit
) {
    @SuppressLint("InflateParams")
    private val view = activity.layoutInflater.inflate(R.layout.time_picker, null)
    private val timePicker = view.findViewById<TimePicker>(R.id.timePicker)
    private val remainingTime = view.findViewById<TextView>(R.id.remainingTime)
    private lateinit var timeUpdater: () -> Unit
    private var time: Long = initTime

    init {
        timePicker.setOnTimeChangedListener { _, hourOfDay, minute ->
            time = getUpComingTimeStamp(hourOfDay, minute)
        }
        val (hour, minute, amPm) = toHourAndMinuteRaw(time)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            timePicker.currentHour = hour + amPm * 12
            timePicker.currentMinute = minute
        } else {
            timePicker.hour = hour + amPm * 12
            timePicker.minute = minute
        }
        timeUpdater = {
            val (hours, minutes, seconds) = partitionTimeDiff(time - now())
            @SuppressLint("SetTextI18n")
            remainingTime.text = ("Download will start after<br>" +
                    "<b>$hours hours $minutes minutes and $seconds seconds</b>").asHtml()
            activity.handler.postDelayed(timeUpdater, 500)
        }
        timeUpdater()
    }

    private fun getUpComingTimeStamp(hourOfDay: Int, minute: Int): Long {
        val calendar = GregorianCalendar.getInstance()
        calendar.timeZone = TimeZone.getDefault()
        calendar.time = Date()
        calendar[Calendar.HOUR_OF_DAY] = hourOfDay
        calendar[Calendar.MINUTE] = minute
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0
        if (calendar.time.time < now())
            calendar.time = Date(calendar.time.time + 24.hours)
        return calendar.time.time
    }

    fun show(): AlertDialog = AlertDialog.Builder(activity)
        .setView(view)
        .setPositiveButton("OK") { d, _ -> onTimeSelected(time); d.dismiss() }
        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        .setNeutralButton("Delete") { d, _ -> onScheduleRemoved(); d.dismiss() }
        .setOnDismissListener { activity.handler.removeCallbacks(timeUpdater) }
        .setOnCancelListener { activity.handler.removeCallbacks(timeUpdater) }
        .show()

    companion object {
        fun partitionTimeDiff(timeDiff: Long): Triple<Int, Int, Int> {
            val preciseHour = timeDiff / 3600.0 / 1000.0
            val hours = preciseHour.toInt()
            val minutes = ((preciseHour - hours) * 60).toInt()
            val seconds = ((preciseHour - hours - minutes / 60.0) * 3600).toInt()
            return Triple(hours, minutes, seconds)
        }

        private fun toHourAndMinuteRaw(timeStamp: Long): Triple<Int, Int, Int> {
            val calendar = GregorianCalendar.getInstance()
            calendar.time = Date(timeStamp)
            calendar.timeZone = TimeZone.getDefault()
            return Triple(
                calendar[Calendar.HOUR],
                calendar[Calendar.MINUTE],
                calendar[Calendar.AM_PM]
            )
        }
        fun toHourAndMinute(timeStamp: Long): String {
            fun insertZeroes(int: Int) = if (int < 10) "0$int" else int.toString()
            val (hour, minute, amPm) = toHourAndMinuteRaw(timeStamp)
            return insertZeroes(hour) + ":" + insertZeroes(minute) +
                    if (amPm == 0) "AM" else "PM"
        }
    }
}