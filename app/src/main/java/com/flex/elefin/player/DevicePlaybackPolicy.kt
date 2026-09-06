package com.flex.elefin.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.flex.elefin.util.hasTightMemory

object DevicePlaybackPolicy {
    @Volatile var current = PlaybackPolicy()
        private set

    /** API 21's VideoCapabilities checks size and rate together; no API 29-only hardware flags. */
    fun initialize(context: Context) {
        val lowMemory = context.hasTightMemory()
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        val supported = mutableListOf<VideoSupport>()
        for ((codec, mime) in listOf("h264" to "video/avc", "hevc" to "video/hevc", "vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01")) {
            val candidates = infos.filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } &&
                !it.name.startsWith("OMX.google.", true) && !it.name.startsWith("c2.android.", true) }
            val sizes = if (lowMemory) listOf(1920 to 1080, 1280 to 720, 854 to 480) else listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
            val decoder = candidates.mapNotNull { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(mime)
                    val size = sizes.firstOrNull { (width, height) -> caps.videoCapabilities?.areSizeAndRateSupported(width, height, 30.0) == true }
                        ?: return@runCatching null
                    val profiles = when (codec) {
                        "h264" -> caps.profileLevels.mapNotNull { level -> when (level.profile) {
                            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "baseline"
                            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "main"
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "high"
                            else -> null
                        } }.distinct()
                        "hevc" -> caps.profileLevels.mapNotNull { level -> when (level.profile) {
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "main"
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "main10"
                            else -> null
                        } }.distinct()
                        else -> emptyList()
                    }
                    VideoSupport(codec, size.first, size.second, profiles = profiles)
                }.getOrNull()
            }.maxByOrNull { it.width * it.height }
            if (decoder != null) supported.add(decoder)
        }
        if (supported.none { it.codec == "h264" }) supported.add(PlaybackPolicy().h264)
        current = PlaybackPolicy(supported, maxBitrate = if (lowMemory) 10_000_000 else 24_000_000, lowMemory = lowMemory)
    }
}
