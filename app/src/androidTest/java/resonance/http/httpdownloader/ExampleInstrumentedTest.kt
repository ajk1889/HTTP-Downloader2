package resonance.http.httpdownloader

import android.view.KeyEvent
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import org.hamcrest.CoreMatchers.anything
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import resonance.http.httpdownloader.activities.Browser
import resonance.http.httpdownloader.activities.MainActivity
import resonance.http.httpdownloader.helpers.Pref
import resonance.http.httpdownloader.helpers.Server
import java.lang.Thread.sleep
import kotlin.contracts.ExperimentalContracts

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    private val testServer = Server()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun init() {
        testServer.bufferSize = 1024
        Server.sleep = 10
        testServer.start()
    }

    @get:Rule
    val mainActivityRule = ActivityTestRule(MainActivity::class.java)

    @ExperimentalContracts
    @Test
    fun simpleDownloadTest() {
        Pref.reviewRequestCounter = 0
        onView(withId(R.id.add_1)).perform(click())
        var monitor = instrumentation.addMonitor(Browser::class.java.name, null, false)
        onView(withId(R.id.browser)).perform(click())
        monitor.waitForActivity()
        for (id in listOf(android.R.id.button2, android.R.id.button1)) {
            onView(withId(R.id.address)).perform(click())
            sleep(1500)
            onView(withId(R.id.searchText)).perform(
                pressKey(getKey(KeyEvent.KEYCODE_DEL)),
                typeText("localhost:1234/100mb.txt"),
                pressKey(getKey(KeyEvent.KEYCODE_ENTER))
            )
            sleep(1000)
            onView(withId(id)).perform(click())
            sleep(500)
        }
        monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        onView(withText("VIEW")).perform(click())
        val mainActivity = monitor.waitForActivity() as MainActivity
        val listItem = onData(anything()).inAdapterView(withId(R.id.downloads_list)).atPosition(0)
        for (i in 0..6) {
            listItem.perform(click())
            sleep(400)
        }
        for (i in 0..4) {
            sleep(400)
            listItem.onChildView(withId(R.id.pauseIcon)).perform(click())
        }
        Server.sleep = 0

        sleep(5000)
    }
}
