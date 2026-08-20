package com.flex.elefin.ui

import android.content.Context
import android.content.res.Configuration

/**
 * Device type detection helpers.
 *
 * NOTE: This file was missing from the upstream repository (imported by many
 * screens but never committed). Recreated for this fork based on its usages:
 * `DeviceUtils.isTvDevice(context): Boolean`.
 */
object DeviceUtils {

    /**
     * Returns true when the device is a TV (leanback / TV form factor).
     * Mirrors the standard checks used across the Android ecosystem:
     * a device is considered a TV when it has leanback support or is in
     * UI_MODE_TYPE_TELEVISION (also covers Fire TV / Android TV boxes).
     */
    fun isTvDevice(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature("android.software.leanback")
    }
}
