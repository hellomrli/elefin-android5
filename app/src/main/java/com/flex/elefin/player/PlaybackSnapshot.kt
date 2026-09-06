package com.flex.elefin.player

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackSnapshot(
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long = 0,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val savedAt: Long = System.currentTimeMillis()
) {
    val safePositionMs: Long get() = positionMs.coerceAtLeast(0).let {
        if (durationMs > 0) it.coerceAtMost(durationMs) else it
    }
    val completed: Boolean get() = durationMs > 0 && safePositionMs.toDouble() / durationMs >= 0.90
    val positionTicks: Long get() = safePositionMs.coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000
}

/** Zero is a real position after playback was ready (for example after seeking to the start). */
fun fallbackPosition(currentMs: Long, requestedResumeMs: Long, hasBeenReady: Boolean): Long =
    if (hasBeenReady || currentMs > 0) currentMs.coerceAtLeast(0) else requestedResumeMs.coerceAtLeast(0)
