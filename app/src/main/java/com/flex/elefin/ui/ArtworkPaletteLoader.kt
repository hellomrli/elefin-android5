package com.flex.elefin.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.flex.elefin.jellyfin.JellyfinApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ArtworkPaletteLoader {
    private val palettes = LruCache<String, ArtworkPalette>(96)

    suspend fun load(context: Context, url: String, api: JellyfinApiService?): ArtworkPalette? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val source = url.toHttpUrlOrNull() ?: return@withContext null
        val server = api?.serverBaseUrl?.toHttpUrlOrNull()
        val isJellyfin = server != null && source.scheme == server.scheme && source.host == server.host &&
            source.port == server.port && source.encodedPath.startsWith(server.encodedPath.trimEnd('/') + "/")
        val thumbnail = when {
            isJellyfin -> source.newBuilder()
                .setQueryParameter("maxWidth", "128").setQueryParameter("maxHeight", "128")
                .setQueryParameter("quality", "75").build().toString()
            source.host == "image.tmdb.org" && source.encodedPath.startsWith("/t/p/") ->
                source.newBuilder().encodedPath(source.encodedPath.replaceFirst(Regex("^/t/p/[^/]+/"), "/t/p/w300/")).build().toString()
            else -> url
        }
        // Keep the image tag and account in the key; never put credentials in cache keys.
        val cacheKey = "${if (isJellyfin) api?.getUserId() else ""}|$thumbnail"
        palettes.get(cacheKey)?.let { return@withContext it }
        val request = ImageRequest.Builder(context.applicationContext)
            .data(thumbnail).size(128, 128).allowHardware(false)
            .apply { if (isJellyfin) headers(api!!.getImageRequestHeaders()) }
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
        PlexPaletteExtractor.extract(context.applicationContext, bitmap).also { palettes.put(cacheKey, it) }
    }

    fun clear() = palettes.evictAll()
}
