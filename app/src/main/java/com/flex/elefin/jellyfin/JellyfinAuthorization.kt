package com.flex.elefin.jellyfin

import com.flex.elefin.BuildConfig
import java.net.URLEncoder

/** Authentication supported by Jellyfin 10.10+ and by 12 with legacy auth disabled. */
object JellyfinAuthorization {
    const val HEADER_NAME = "Authorization"
    const val QUERY_PARAMETER = "ApiKey"

    fun header(
        accessToken: String? = null,
        deviceId: String = "",
        clientName: String = "Elefin",
        deviceName: String = "Android TV",
        version: String = BuildConfig.VERSION_NAME
    ): String = listOf(
        "Client" to clientName,
        "Device" to deviceName,
        "DeviceId" to deviceId,
        "Version" to version,
        "Token" to accessToken
    ).filter { it.second != null }.joinToString(
        separator = ", ",
        prefix = "MediaBrowser "
    ) { (key, value) ->
        // The official SDK percent-encodes values; the server URL-decodes them.
        // Keep quotes, commas and non-ASCII names inside a single header parameter.
        val encoded = URLEncoder.encode(value!!.trim(), "UTF-8").replace("+", "%20")
        key + "=\"" + encoded + "\""
    }
}
