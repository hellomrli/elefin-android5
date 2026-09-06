package com.flex.elefin.player

import kotlinx.serialization.json.*

data class VideoSupport(val codec: String, val width: Int, val height: Int, val frameRate: Int = 30, val profiles: List<String> = emptyList())

/** Conservative fallbacks are also used while device capabilities are being discovered. */
data class PlaybackPolicy(
    val video: List<VideoSupport> = listOf(VideoSupport("h264", 1280, 720, profiles = listOf("baseline"))),
    val maxBitrate: Int = 8_000_000,
    val audioChannels: Int = 2,
    val lowMemory: Boolean = true
) {
    val h264: VideoSupport get() = video.firstOrNull { it.codec == "h264" } ?: VideoSupport("h264", 1280, 720, profiles = listOf("baseline"))
    fun fallback(requestedCodec: String): VideoSupport =
        video.firstOrNull { !lowMemory && requestedCodec.equals("hevc", true) && it.codec == "hevc" } ?: h264
    fun bitrate(requestedMbps: Int): Int = (requestedMbps.coerceAtLeast(1).toLong() * 1_000_000).coerceAtMost(maxBitrate.toLong()).toInt()

    fun canDirectPlay(codec: String?, width: Int?, height: Int?, fps: Double?, profile: String?): Boolean {
        if (codec.isNullOrBlank()) return true // Let MediaCodec inspect streams with missing metadata.
        val normalized = when (codec.lowercase()) { "h265" -> "hevc"; "av01" -> "av1"; else -> codec.lowercase() }
        val support = video.firstOrNull { it.codec == normalized } ?: return false
        return (width ?: 0) <= support.width && (height ?: 0) <= support.height &&
            (fps ?: 0.0) <= support.frameRate + 0.1 && (profile.isNullOrBlank() || support.profiles.isEmpty() ||
            support.profiles.any { it.equals(profile.replace(" ", ""), ignoreCase = true) })
    }

    fun deviceProfile(): JsonObject = buildJsonObject {
        put("Name", "Elefin API 21")
        put("MaxStreamingBitrate", maxBitrate)
        put("MaxStaticBitrate", maxBitrate)
        putJsonArray("DirectPlayProfiles") {
            add(buildJsonObject {
                put("Type", "Video"); put("Container", "mp4,mkv,mov,webm,ts,mpegts")
                put("VideoCodec", video.joinToString(",") { it.codec })
                put("AudioCodec", "aac,ac3,eac3,mp3,flac,alac,opus,vorbis,dts,truehd")
            })
        }
        putJsonArray("TranscodingProfiles") {
            add(buildJsonObject {
                put("Type", "Video"); put("Container", "ts"); put("Protocol", "hls")
                put("Context", "Streaming"); put("VideoCodec", "h264"); put("AudioCodec", "aac")
                put("MaxAudioChannels", audioChannels.toString()); put("MinSegments", 1)
            })
        }
        putJsonArray("CodecProfiles") {
            video.forEach { support ->
                add(buildJsonObject {
                    put("Type", "Video"); put("Codec", support.codec)
                    putJsonArray("Conditions") {
                        for ((property, value) in listOf("Width" to support.width, "Height" to support.height,
                            "VideoFramerate" to support.frameRate, "VideoBitrate" to maxBitrate)) {
                            add(buildJsonObject {
                                put("Condition", "LessThanEqual"); put("Property", property)
                                put("Value", value.toString()); put("IsRequired", false)
                            })
                        }
                        if (support.profiles.isNotEmpty()) add(buildJsonObject {
                            put("Condition", "EqualsAny"); put("Property", "VideoProfile")
                            put("Value", support.profiles.joinToString("|")); put("IsRequired", false)
                        })
                    }
                })
            }
        }
    }
}
