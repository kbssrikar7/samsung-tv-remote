package com.kbs.tvremote

import org.junit.Assert.*
import org.junit.Test

class TvInfraredTest {
    private class FakeEmitter : TvInfrared.Emitter {
        var exists = true
        var ranges: List<IntRange>? = listOf(30_000..50_000)
        var fail = false
        var carrier = 0
        var sent: IntArray? = null
        override fun present() = exists
        override fun frequencies() = ranges
        override fun transmit(carrier: Int, pattern: IntArray) {
            if (fail) throw IllegalStateException("IR service unavailable")
            this.carrier = carrier
            sent = pattern
        }
    }

    @Test fun absentHardwareNeverClaimsToSend() {
        val emitter = FakeEmitter().apply { exists = false }
        val infrared = TvInfrared(emitter)
        assertFalse(infrared.available)
        assertFalse(infrared.send(TvInfrared.KEY_POWER_ON))
        assertNull(emitter.sent)
    }

    @Test fun unsupportedCarrierAndUnknownKeyAreRejected() {
        val emitter = FakeEmitter().apply { ranges = listOf(40_000..60_000) }
        assertFalse(TvInfrared(emitter).send(TvInfrared.KEY_POWER_ON))
        emitter.ranges = listOf(30_000..50_000)
        assertFalse(TvInfrared(emitter).send("KEY_FACTORY"))
        assertNull(emitter.sent)
    }

    @Test fun sendsOneShortFrameAt38KhzAndHandlesDriverFailure() {
        val emitter = FakeEmitter()
        val infrared = TvInfrared(emitter)
        assertTrue(infrared.send(TvInfrared.KEY_POWER_ON))
        assertEquals(38_000, emitter.carrier)
        assertTrue(emitter.sent!!.sum() < 2_000_000)
        emitter.fail = true
        assertFalse(infrared.send("KEY_ENTER"))
    }

    @Test fun unknownFrequencyQueryCanAttemptTransmitButEmptyRangesCannot() {
        val emitter = FakeEmitter().apply { ranges = null }
        assertTrue(TvInfrared(emitter).send("KEY_ENTER"))
        emitter.ranges = emptyList()
        assertFalse(TvInfrared(emitter).available)
    }

    @Test fun waveformMatchesCapturedSamsungCommandsIncludingOkNotReturn() {
        val expected = mapOf(TvInfrared.KEY_POWER_ON to 0xE0E09966L, "KEY_UP" to 0xE0E006F9L,
            "KEY_DOWN" to 0xE0E08679L, "KEY_LEFT" to 0xE0E0A659L,
            "KEY_RIGHT" to 0xE0E046B9L, "KEY_ENTER" to 0xE0E016E9L)
        expected.forEach { (key, code) ->
            val frame = TvInfrared.pattern(key)!!
            assertEquals(67, frame.size)
            assertArrayEquals(intArrayOf(4480, 4480), frame.copyOfRange(0, 2))
            var decoded = 0L
            repeat(32) { bit ->
                assertEquals(560, frame[2 + bit * 2])
                val space = frame[3 + bit * 2]
                assertTrue(space == 560 || space == 1680)
                decoded = (decoded shl 1) or if (space == 1680) 1L else 0L
            }
            assertEquals(key, code, decoded)
            assertEquals(560, frame.last())
        }
    }

    /** The toggle would turn off a TV that is on but not connected yet, so it is never sent. */
    @Test fun powerToggleIsNotOffered() {
        assertNull(TvInfrared.pattern("KEY_POWER"))
    }
}
