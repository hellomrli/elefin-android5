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
fun Context.hasTightMemory(): Boolean = try {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null && am.isLowRamDevice) {
        true
    } else if (am != null) {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem < 2_500_000_000L // 2.5 GB decimal
    } else {
        false
    }
} catch (e: Exception) {
    false
}
