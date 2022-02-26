package resonance.http.httpdownloader

import androidx.test.espresso.action.EspressoKey

fun getKey(code: Int): EspressoKey = EspressoKey.Builder().withKeyCode(code).build()