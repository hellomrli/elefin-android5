package com.flex.elefin

import android.app.Application
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

import org.schabi.newpipe.extractor.NewPipe
import com.flex.elefin.networking.ElefinDownloader
import com.flex.elefin.util.hasTightMemory

class ElefinApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(ElefinDownloader())
        com.flex.elefin.player.PlaybackReports.initialize(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            // The old non-reclaimable cache is disposable, not user data.
            filesDir.resolve("image_cache").deleteRecursively()
            com.flex.elefin.player.DevicePlaybackPolicy.initialize(this@ElefinApplication)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val imageMemoryBytes = (if (hasTightMemory()) 20L else 48L) * 1024 * 1024
        val diskBytes = (cacheDir.usableSpace / 50).coerceIn(32L * 1024 * 1024, 192L * 1024 * 1024)

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(imageMemoryBytes.toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(diskBytes)
                    .build()
            }
            .respectCacheHeaders(false) // Always cache images regardless of HTTP headers
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
