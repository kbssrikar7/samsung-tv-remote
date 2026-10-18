package com.kbs.tvremote

import android.content.Context
import android.content.SharedPreferences

/** Where the app keeps the chosen TV and its pairing data; shared by the app and the widget. */
object TvPrefs {
    const val IP = "ip"
    fun token(ip: String) = "token_$ip"
    fun mac(ip: String) = "mac_$ip"
    fun name(ip: String) = "name_$ip"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
}
