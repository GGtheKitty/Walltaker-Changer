package com.gios.walltakerchanger

import android.content.SharedPreferences
import android.net.Uri

const val DEFAULT_WALLTAKER_SERVER = "https://walltaker.joi.how"

fun walltakerServer(sharedPreferences: SharedPreferences): String {
    val savedServer = sharedPreferences.getString("serverUrl", DEFAULT_WALLTAKER_SERVER)
    val rawServer = savedServer?.trim().orEmpty()
    val serverWithScheme = when {
        rawServer.isEmpty() -> DEFAULT_WALLTAKER_SERVER
        rawServer.startsWith("http://") || rawServer.startsWith("https://") -> rawServer
        else -> "https://$rawServer"
    }
    return serverWithScheme.trimEnd('/')
}

fun walltakerCableUrl(sharedPreferences: SharedPreferences): String {
    val server = Uri.parse(walltakerServer(sharedPreferences))
    val scheme = if (server.scheme == "http") "ws" else "wss"
    return server.buildUpon()
        .scheme(scheme)
        .encodedPath("cable")
        .clearQuery()
        .fragment(null)
        .build()
        .toString()
}
