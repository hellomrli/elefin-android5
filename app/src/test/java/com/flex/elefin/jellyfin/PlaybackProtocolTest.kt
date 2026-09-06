package com.flex.elefin.jellyfin

import com.flex.elefin.player.PlaybackSnapshot
import com.flex.elefin.player.fallbackPosition
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackProtocolTest {
    @Test fun stoppedPayloadHasNoTrailingCommaWithAnyTrackCombination() {
        for (audio in listOf(null, 2)) for (subtitle in listOf(null, -1, 3)) {
            val json = Json.parseToJsonElement(buildPlaybackStoppedPayload("a\"b", 123_450_000, audio, subtitle)).jsonObject
            assertEquals("a\"b", json.getValue("ItemId").jsonPrimitive.content)
            assertEquals(123_450_000, json.getValue("PositionTicks").jsonPrimitive.long)
            assertEquals(audio, json["AudioStreamIndex"]?.jsonPrimitive?.int)
            assertEquals(subtitle, json["SubtitleStreamIndex"]?.jsonPrimitive?.int)
        }
    }

    @Test fun finalSnapshotUsesPlaybackTimelineAndCompletionThreshold() {
        val resume = PlaybackSnapshot("movie", 600_000, 1_000_000, 2, -1)
        assertEquals(6_000_000_000, resume.positionTicks)
        assertFalse(resume.completed)
        assertTrue(resume.copy(positionMs = 900_000).completed)
        assertFalse(resume.copy(durationMs = -1).completed)
        assertEquals(0, resume.copy(positionMs = -100).positionTicks)
        assertEquals(1_000_000, resume.copy(positionMs = Long.MAX_VALUE).safePositionMs)
    }

    @Test fun discoveryTvResultRetainsSeriesProtocolTypeForRouting() {
        val item = JellyfinItem("jellyseerr_123", "A series", Type = MediaTypes.fromDiscovery("tv"))
        assertEquals(MediaTypes.SERIES, item.Type)
        assertEquals("tv", MediaTypes.toDiscovery(item.Type))
        assertEquals("movie", MediaTypes.toDiscovery(MediaTypes.fromDiscovery("movie")))
        assertNull(MediaTypes.fromDiscovery("person"))
        assertNull(MediaTypes.toDiscovery("剧集"))
    }

    @Test fun fallbackPreservesCurrentPositionIncludingBackwardSeeks() {
        assertEquals(600_000, fallbackPosition(600_000, 120_000, true))
        assertEquals(30_000, fallbackPosition(30_000, 120_000, true))
        assertEquals(0, fallbackPosition(0, 120_000, true))
        assertEquals(120_000, fallbackPosition(0, 120_000, false))
    }
}
