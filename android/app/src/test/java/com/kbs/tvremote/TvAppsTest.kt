package com.kbs.tvremote

import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvAppsTest {

    @Test
    fun savedShortcutsForAppsNoLongerOfferedAreDropped() {
        val prefs = TvPrefs.of(RuntimeEnvironment.getApplication())
        val ip = "192.168.1.20"
        // Saved by an older version that offered more shortcuts.
        prefs.edit {
            putString(TvPrefs.apps(ip), """[
                {"id":"111299001912","name":"YouTube","kind":"YOUTUBE"},
                {"id":"3201711015117","name":"JioHotstar","kind":"VIDEO"}
            ]""")
        }

        assertEquals(listOf("YouTube"), TvApps.load(prefs, ip).map { it.name })
    }
}
