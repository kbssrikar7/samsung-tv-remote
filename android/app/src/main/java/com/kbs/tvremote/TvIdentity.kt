package com.kbs.tvremote

/** Stable identifiers from Samsung's device-info endpoint. A display name is never an identity. */
data class TvIdentity(val deviceId: String? = null, val mac: String? = null) {
    val known: Boolean get() = deviceId != null || mac != null

    fun matches(other: TvIdentity): Boolean = when {
        deviceId != null && other.deviceId != null -> deviceId == other.deviceId
        mac != null && other.mac != null -> mac == other.mac
        else -> false
    }

    companion object {
        fun from(deviceId: String?, mac: String?): TvIdentity {
            val id = deviceId?.trim()?.lowercase()?.removePrefix("uuid:")
                ?.takeIf { it.isNotBlank() && it !in setOf("unknown", "null", "none") }
            val address = mac?.replace(":", "")?.replace("-", "")?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{12}")) && it != "000000000000" && it != "ffffffffffff" }
            return TvIdentity(id, address)
        }
    }
}
