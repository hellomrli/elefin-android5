package com.flex.elefin.jellyfin

/** Jellyfin protocol values. Translated labels belong only in the UI. */
object MediaTypes {
    const val MOVIE = "Movie"
    const val SERIES = "Series"
    const val EPISODE = "Episode"

    fun fromDiscovery(type: String?): String? = when (type) {
        "tv" -> SERIES
        "movie" -> MOVIE
        else -> null
    }

    fun toDiscovery(type: String?): String? = when (type) {
        SERIES -> "tv"
        MOVIE -> "movie"
        else -> null
    }
}
