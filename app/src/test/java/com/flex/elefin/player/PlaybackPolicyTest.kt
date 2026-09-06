package com.flex.elefin.player

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    private val codecs = listOf(VideoSupport("h264", 1920, 1080, profiles = listOf("baseline", "main", "high")),
        VideoSupport("hevc", 3840, 2160, profiles = listOf("main")))

    @Test fun weakDeviceTranscodesToH264WithinBudgetEvenWhenUserRequests4kHevc() {
        val policy = PlaybackPolicy(codecs, maxBitrate = 10_000_000)
        assertEquals("h264", policy.fallback("hevc").codec)
        assertEquals(1920, policy.fallback("hevc").width)
        assertEquals(10_000_000, policy.bitrate(40))
        assertEquals(10_000_000, policy.bitrate(Int.MAX_VALUE))
        assertEquals(2, policy.audioChannels)
    }

    @Test fun directPlayChecksCodecProfileSizeAndRate() {
        val policy = PlaybackPolicy(codecs)
        assertTrue(policy.canDirectPlay("h264", 1920, 1080, 29.97, "High"))
        assertFalse(policy.canDirectPlay("av1", 1920, 1080, 24.0, null))
        assertFalse(policy.canDirectPlay("h264", 3840, 2160, 24.0, "High"))
        assertFalse(policy.canDirectPlay("h264", 1920, 1080, 60.0, "High"))
        assertFalse(policy.canDirectPlay("hevc", 1920, 1080, 24.0, "Main 10"))
    }

    @Test fun strongerDeviceCanUseItsAdvertisedHevcFallback() {
        assertEquals("hevc", PlaybackPolicy(codecs, lowMemory = false).fallback("hevc").codec)
        assertEquals("h264", PlaybackPolicy(codecs, lowMemory = false).fallback("av1").codec)
    }

    @Test fun playbackInfoIncludesCodecLimitsAndHlsAudioFallback() {
        val profile = PlaybackPolicy().deviceProfile()
        assertEquals(8_000_000, profile.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        val transcode = profile.getValue("TranscodingProfiles").jsonArray.single().jsonObject
        assertEquals("h264", transcode.getValue("VideoCodec").jsonPrimitive.content)
        assertEquals("aac", transcode.getValue("AudioCodec").jsonPrimitive.content)
        val conditions = profile.getValue("CodecProfiles").jsonArray.first().jsonObject.getValue("Conditions").jsonArray
        assertTrue(conditions.any { it.jsonObject["Property"]?.jsonPrimitive?.content == "Width" && it.jsonObject["Value"]?.jsonPrimitive?.content == "1280" })
    }
}
