package com.kbs.tvremote

import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class KeyServiceTest {

    /** Android 8 kills the app if startForeground isn't called; Android 14+ also needs the type. */
    @Test
    @Config(sdk = [26, 34, 35])
    fun widgetTapGoesForegroundAndStopsWhenDone() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, KeyService::class.java).putExtra("key", "KEY_VOLUP")
        val controller = Robolectric.buildService(KeyService::class.java, intent).create()
        val service = controller.get()

        controller.startCommand(0, 1)

        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(2, shadow.lastForegroundNotificationId)
        // No TV is saved, so the send fails fast and the newest tap stops the service.
        val deadline = System.nanoTime() + 5_000_000_000
        while (!shadow.isStoppedBySelf && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(shadow.isStoppedBySelf)
    }
}
