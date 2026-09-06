package com.flex.elefin.util

import android.app.ActivityManager
import android.content.Context

/**
 * Whether this device should run with reduced memory budgets.
 *
 * [ActivityManager.isLowRamDevice] alone is not enough: most Android 5 TV boxes never
 * set `ro.config.low_ram`, so a 1 GB box reports `false`. The total-RAM fallback is what
 * actually catches them.
 *
 * Used to size the image cache and the player's media buffer. Getting this wrong on a
 * 1 GB box means LMK starts killing background apps mid-playback.
 */
@Volatile private var tightMemory: Boolean? = null

fun Context.hasTightMemory(): Boolean {
    tightMemory?.let { return it }
    return synchronized(ActivityManager::class.java) {
        tightMemory ?: detectTightMemory().also { tightMemory = it }
    }
}

private fun Context.detectTightMemory(): Boolean = try {
    val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(info)
    am != null && (am.isLowRamDevice || info.totalMem < 2_500_000_000L)
} catch (_: Exception) {
    true // Choose a bounded budget if the device cannot report its memory class.
}
