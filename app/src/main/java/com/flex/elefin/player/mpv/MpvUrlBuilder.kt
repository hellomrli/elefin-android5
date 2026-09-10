package com.flex.elefin.player.mpv

import com.flex.elefin.BuildConfig
import com.flex.elefin.jellyfin.JellyfinAuthorization

/**
 * Builds Jellyfin-compatible URLs for MPV playback.
 * 
 * Uses the supported Authorization header and ApiKey query parameter.
 */
object MpvUrlBuilder {
    
    /**
     * Build HTTP headers for Jellyfin authentication.
     * Uses CRLF line endings as required by MPV.
     */
    fun buildHeaders(
        accessToken: String,
        deviceId: String,
        clientName: String = "Elefin",
        version: String = BuildConfig.VERSION_NAME
    ): String = buildString {
        append("User-Agent: $clientName/MPV\r\n")
        append(JellyfinAuthorization.HEADER_NAME).append(": ")
        append(JellyfinAuthorization.header(accessToken, deviceId, clientName, "AndroidTV", version))
        append("\r\n")
        append("Accept: */*\r\n")
    }
    
    /**
     * Build direct stream URL for Jellyfin.
     * 
     * Always uses static=true for direct streaming - resume is handled client-side by MPV.
     */
    fun buildStreamUrl(
        serverUrl: String,
        itemId: String,
        accessToken: String,
        mediaSourceId: String? = null,
        container: String? = null,
        startTimeTicks: Long? = null // Ignored - resume handled client-side
    ): String {
        val baseUrl = serverUrl.removeSuffix("/")
        return buildString {
            append("$baseUrl/Videos/$itemId/stream?")
            // Always use static=true for direct streaming without transcoding
            // Resume position is handled client-side by MPV seeking after load
            append("static=true")
            append("&ApiKey=$accessToken")
            append("&mediaSourceId=${mediaSourceId ?: itemId}")
            append("&enableAutoStreamCopy=true")
            append("&allowVideoStreamCopy=true")
            append("&allowAudioStreamCopy=true")
            container?.let { append("&container=$it") }
        }
    }
    
    /**
     * Build direct download URL for Jellyfin.
     * This is the most compatible option.
     */
    fun buildDownloadUrl(
        serverUrl: String,
        itemId: String,
        accessToken: String,
        mediaSourceId: String? = null
    ): String {
        val baseUrl = serverUrl.removeSuffix("/")
        return "$baseUrl/Items/$itemId/Download?ApiKey=$accessToken&mediaSourceId=${mediaSourceId ?: itemId}"
    }
}

