package com.kbs.tvremote.youtube

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoungeFramesTest {
    @Test fun readsAdjacentFramesWithoutRequiringPayloadLineBreaks() {
        val first = "[[1,[\"nowPlaying\",{\"title\":\"hello 🌍\"}]]]"
        val second = "[[2,[\"noop\"]]]"
        val source = Buffer().writeUtf8("${first.length}\n$first${second.length}\n$second")
        assertEquals("hello 🌍", LoungeFrames.read(source)!!.getJSONArray(0).getJSONArray(1).getJSONObject(1).getString("title"))
        assertEquals(2, LoungeFrames.read(source)!!.getJSONArray(0).getInt(0))
        assertNull(LoungeFrames.read(source))
    }

    @Test fun preservesNewlinesIncludedInDeclaredLength() {
        val json = "[\n [3,[\"noop\"]]\n]\n"
        val source = Buffer().writeUtf8("${json.length}\n$json")
        assertEquals(3, LoungeFrames.read(source)!!.getJSONArray(0).getInt(0))
        assertTrue(source.exhausted())
    }

    @Test fun rejectsInvalidLengthsAndTruncatedOrMalformedPayloads() {
        listOf("-1\n", "2147483647\n", "nope\n", "100\n[]", "2\nxx", "1\n🌍")
            .forEach { frame -> assertThrows(frame, IOException::class.java) { LoungeFrames.read(Buffer().writeUtf8(frame)) } }
    }
}
