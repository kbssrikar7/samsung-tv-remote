package com.kbs.tvremote

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvRecoveryTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("recovery", Context.MODE_PRIVATE)
    private val old = "192.168.1.10"
    private val new = "192.168.1.25"
    private val tv = TvDiscovery.FoundTv(new, "Living room", "uuid:device-one", "AA:BB:CC:DD:EE:01")

    @Before fun setup() { prefs.edit().clear().putString(TvPrefs.IP, old).commit() }

    @Test fun matchesIdentityRatherThanNameOrSingleResult() {
        val saved = tv.identity
        assertNull(TvRecovery.match(saved, listOf(tv.copy(deviceId = "device-two", mac = "AA:BB:CC:DD:EE:02"))))
        assertSame(tv, TvRecovery.match(saved, listOf(tv.copy(ip = "192.168.1.30", deviceId = "different", mac = null), tv)))
        assertNull(TvRecovery.match(TvIdentity(), listOf(tv)))
    }

    @Test fun conflictingDeviceIdsCannotMatchViaSameMac() {
        assertFalse(tv.identity.matches(tv.copy(deviceId = "device-two").identity))
    }

    @Test fun existingMacOnlyPairingCanRecoverAndLearnDeviceId() {
        prefs.edit().putString(TvPrefs.mac(old), "aa-bb-cc-dd-ee-01").commit()
        assertTrue(TvRecovery.relocate(prefs, old, tv))
        assertEquals("device-one", prefs.getString(TvPrefs.id(new), null))
    }

    @Test fun duplicateIdentityAtDifferentAddressesNeedsExplicitSelection() {
        assertNull(TvRecovery.match(tv.identity, listOf(tv, tv.copy(ip = "192.168.1.30"))))
    }

    @Test fun migrationPreservesOriginalPinTokenAppsAndYoutubeLink() {
        prefs.edit().putString(TvPrefs.id(old), "device-one")
            .putString(TvPrefs.token(old), "paired-token").putString(TvClient.pinKey(old), "original-pin")
            .putString(TvClient.pinKey(new), "another-tv-old-pin")
            .putBoolean("legacy_$old", false).putString(TvPrefs.apps(old), "cached-apps")
            .putString("yt_screen_id", "linked-screen").commit()
        assertTrue(TvRecovery.relocate(prefs, old, tv))
        assertEquals(new, prefs.getString(TvPrefs.IP, null))
        assertEquals("original-pin", prefs.getString(TvClient.pinKey(new), null))
        assertEquals("paired-token", prefs.getString(TvPrefs.token(new), null))
        assertEquals("cached-apps", prefs.getString(TvPrefs.apps(new), null))
        assertEquals("linked-screen", prefs.getString("yt_screen_id", null))
        assertFalse(prefs.contains(TvPrefs.token(old)))
        assertFalse(prefs.contains(TvClient.pinKey(old)))
    }

    @Test fun staleRecoveryCannotReplaceNewSelection() {
        prefs.edit().putString(TvPrefs.id(old), "device-one").putString(TvPrefs.IP, "192.168.1.99").commit()
        assertFalse(TvRecovery.relocate(prefs, old, tv))
        assertEquals("192.168.1.99", prefs.getString(TvPrefs.IP, null))
    }

    @Test fun mismatchedIdentityCannotMoveCredentials() {
        prefs.edit().putString(TvPrefs.id(old), "different").putString(TvPrefs.token(old), "secret").commit()
        assertFalse(TvRecovery.relocate(prefs, old, tv))
        assertFalse(prefs.contains(TvPrefs.token(new)))
    }

    @Test fun parsesDeviceIdFallbacksAndIgnoresUnknownIdentifiers() {
        val info = JSONObject("""{"device":{"OS":"Tizen","id":"unknown","duid":"UUID:DEVICE-ONE","wifiMac":"aa:bb:cc:dd:ee:01"}}""")
        assertEquals(tv.identity, TvDiscovery.parse(new, info)!!.identity)
        assertFalse(TvIdentity.from("unknown", "00:00:00:00:00:00").known)
        assertNull(TvDiscovery.parse(new, JSONObject("""{"device":{"OS":"Other"}}""")))
    }
}
