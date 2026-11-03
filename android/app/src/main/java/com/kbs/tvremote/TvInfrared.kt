package com.kbs.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager

/** Optional, local Samsung32 setup controls. No network pairing or saved token is involved. */
class TvInfrared internal constructor(private val emitter: Emitter) {
    internal interface Emitter {
        fun present(): Boolean
        fun frequencies(): List<IntRange>?
        fun transmit(carrier: Int, pattern: IntArray)
    }

    constructor(context: Context) : this(object : Emitter {
        private val manager = context.applicationContext.getSystemService(ConsumerIrManager::class.java)
        override fun present() = manager?.hasIrEmitter() == true
        override fun frequencies() = manager?.carrierFrequencies?.map { it.minFrequency..it.maxFrequency }
        override fun transmit(carrier: Int, pattern: IntArray) {
            checkNotNull(manager).transmit(carrier, pattern)
        }
    })

    val available: Boolean get() = runCatching {
        emitter.present() && emitter.frequencies().let { ranges -> ranges == null || ranges.any { CARRIER in it } }
    }.getOrDefault(false)

    /** Blocking (one short IR frame); callers must dispatch away from the UI thread. */
    fun send(key: String): Boolean {
        val pattern = pattern(key) ?: return false
        if (!available) return false
        return runCatching { emitter.transmit(CARRIER, pattern) }.isSuccess
    }

    companion object {
        const val CARRIER = 38_000
        const val KEY_POWER_ON = "KEY_POWERON"
        // On-air MSB-first codes from the Samsung BN59-00865A capture (ENTER-OK, not RETURN).
        // Power is the discrete Power on code, not the remote's toggle: the app can't see whether
        // the TV is on, and a toggle would turn off a TV that is on but not yet connected.
        // Protocol timings: Samsung32, 560 us unit, 8-unit header, 1/3-unit bit spaces.
        // References: docs/REMOTELESS_SETUP.md. These are signals, not vendor SDK code.
        private val codes = mapOf(
            KEY_POWER_ON to 0xE0E09966L,
            "KEY_UP" to 0xE0E006F9L,
            "KEY_DOWN" to 0xE0E08679L,
            "KEY_LEFT" to 0xE0E0A659L,
            "KEY_RIGHT" to 0xE0E046B9L,
            "KEY_ENTER" to 0xE0E016E9L,
        )

        internal fun pattern(key: String): IntArray? {
            val code = codes[key] ?: return null
            return IntArray(67).apply {
                this[0] = 4480
                this[1] = 4480
                repeat(32) { bit ->
                    this[2 + bit * 2] = 560
                    this[3 + bit * 2] = if ((code ushr (31 - bit)) and 1L == 1L) 1680 else 560
                }
                this[66] = 560
            }
        }
    }
}
