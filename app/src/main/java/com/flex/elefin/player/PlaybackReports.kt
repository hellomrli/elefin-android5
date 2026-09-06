package com.flex.elefin.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.flex.elefin.jellyfin.JellyfinApiService
import com.flex.elefin.jellyfin.JellyfinItem
import com.flex.elefin.jellyfin.UserData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

/** The application owns reporting; disposing a player cannot cancel its final report. */
object PlaybackReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(128)
    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("pending_playback", Context.MODE_PRIVATE)
        scope.launch {
            for (report in queue) {
                try { report() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w("PlaybackReports", "Unable to finish queued report", error) }
            }
        }
    }

    internal fun enqueue(action: suspend () -> Unit) {
        if (!queue.trySend(action).isSuccess) scope.launch { queue.send(action) }
    }

    suspend fun awaitPendingReports() {
        val barrier = CompletableDeferred<Unit>()
        enqueue { barrier.complete(Unit) }
        // Returning home can render cached rows immediately while final status catches up.
        withTimeoutOrNull(2_500) { barrier.await() }
    }

    private fun key(api: JellyfinApiService, itemId: String): String =
        MessageDigest.getInstance("SHA-256").digest(
            "${api.serverBaseUrl.trimEnd('/')}|${api.getUserId()}|$itemId".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }

    @Synchronized
    internal fun save(api: JellyfinApiService, snapshot: PlaybackSnapshot) {
        val store = prefs ?: return
        val edit = store.edit()
        // Bounded recovery data; retain only the most recent 200 failed/in-flight items.
        if (store.all.size >= 200) {
            store.all.entries.minByOrNull { (_, value) ->
                runCatching { Json.decodeFromString<PlaybackSnapshot>(value as String).savedAt }.getOrDefault(0)
            }?.let { edit.remove(it.key) }
        }
        edit.putString(key(api, snapshot.itemId), Json.encodeToString(snapshot)).apply()
    }

    @Synchronized
    internal fun acknowledged(api: JellyfinApiService, snapshot: PlaybackSnapshot) {
        val store = prefs ?: return
        val key = key(api, snapshot.itemId)
        val current = store.getString(key, null) ?: return
        if (current == Json.encodeToString(snapshot)) {
            store.edit().remove(key).apply()
        }
    }

    /** Only unsent local data can override older server data; explicit 'play from start' stays intact. */
    fun recover(api: JellyfinApiService, item: JellyfinItem): JellyfinItem {
        val saved = prefs?.getString(key(api, item.Id), null) ?: return item
        val snapshot = runCatching { Json.decodeFromString<PlaybackSnapshot>(saved) }.getOrNull() ?: return item
        val serverTime = runCatching { Instant.parse(item.UserData?.LastPlayedDate).toEpochMilli() }.getOrDefault(0)
        if (serverTime > snapshot.savedAt || System.currentTimeMillis() - snapshot.savedAt > 30L * 24 * 60 * 60 * 1000) return item
        return item.copy(UserData = (item.UserData ?: UserData()).copy(
            PositionTicks = if (snapshot.completed) 0 else snapshot.positionTicks,
            Played = if (snapshot.completed) true else item.UserData?.Played
        ))
    }

    internal suspend fun retry(action: suspend () -> Boolean): Boolean {
        repeat(3) { attempt ->
            val ok = try { withTimeoutOrNull(5_000) { action() } == true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
 Log.w("PlaybackReports", "Playback report failed", error); false }
            if (ok) return true
            if (attempt < 2) delay(500L * (attempt + 1))
        }
        return false
    }
}

class PlaybackReporter(private val api: JellyfinApiService, private val itemId: String) {
    private var closed = false
    private var started = false
    private var progressPending = false

    @Synchronized
    fun reportPlaybackStart(itemId: String, positionTicks: Long = 0, audioStreamIndex: Int? = null, subtitleStreamIndex: Int? = null): Boolean {
        if (closed) return false
        started = true
        PlaybackReports.enqueue { PlaybackReports.retry { api.reportPlaybackStart(itemId, positionTicks, audioStreamIndex, subtitleStreamIndex) } }
        return true
    }

    @Synchronized
    fun reportPlaybackProgress(
        itemId: String, positionTicks: Long, isPaused: Boolean = false, isMuted: Boolean = false,
        volumeLevel: Int = 100, playbackRate: Double = 1.0, audioStreamIndex: Int? = null, subtitleStreamIndex: Int? = null
    ): Boolean {
        if (closed) return false
        val snapshot = PlaybackSnapshot(itemId, positionTicks / 10_000, audioIndex = audioStreamIndex, subtitleIndex = subtitleStreamIndex)
        PlaybackReports.save(api, snapshot)
        if (progressPending) return true
        progressPending = true
        PlaybackReports.enqueue {
            try {
                PlaybackReports.retry { api.reportPlaybackProgress(itemId, positionTicks, isPaused, isMuted, volumeLevel, playbackRate, audioStreamIndex, subtitleStreamIndex) }
            } finally {
                synchronized(this) { progressPending = false }
            }
        }
        return true
    }

    @Synchronized
    fun finish(positionMs: Long, durationMs: Long, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        if (closed) return
        closed = true
        if (!started && positionMs <= 0) return
        val snapshot = PlaybackSnapshot(itemId, positionMs, durationMs, audioIndex, subtitleIndex)
        PlaybackReports.save(api, snapshot)
        PlaybackReports.enqueue {
            val stopped = PlaybackReports.retry { api.reportPlaybackStopped(itemId, snapshot.positionTicks, audioIndex, subtitleIndex) }
            val watched = !snapshot.completed || PlaybackReports.retry { api.markAsWatched(itemId) }
            if (stopped && watched) PlaybackReports.acknowledged(api, snapshot)
        }
    }
}
