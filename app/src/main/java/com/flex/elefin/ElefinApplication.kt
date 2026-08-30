package com.flex.elefin

import android.app.Application
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
    }

    override fun newImageLoader(): ImageLoader {
        // The manifest sets largeHeap, so maxSizePercent is a share of largeMemoryClass.
        // On a 1 GB Android 5 box that is roughly 256 MB, so the old 0.4 handed ~100 MB
        // to bitmaps alone - on top of the player's media buffer. Scale it to the device.
        val memoryFraction = if (hasTightMemory()) 0.15 else 0.25

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memoryFraction)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // Use filesDir instead of cacheDir for better persistence across restarts
                    // filesDir is less likely to be cleared by the system
                    .directory(filesDir.resolve("image_cache"))
                    // Increased to 5% of available disk space (was 2%)
                    // This gives more room for caching while still being reasonable
                    .maxSizePercent(0.05)
                    .build()
            }
            .respectCacheHeaders(false) // Always cache images regardless of HTTP headers
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
