package `is`.xyz.mpv

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_INT64
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_NONE
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_STRING
import java.io.File
import com.flex.elefin.util.hasTightMemory

/**
 * MPV SurfaceView for video rendering.
 * 
 * Handles the MPV lifecycle and provides playback controls.
 */
class MPVView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    
    companion object {
        private const val TAG = "MPVView"
        private const val HWDECS = "mediacodec,mediacodec-copy"
    }

    private var filePath: String? = null
    private var voInUse: String = "gpu"
    private var httpHeaders: String? = null
    private var isInitialized = false
    val session = MpvSession()

    constructor(context: Context) : this(context, null)

    /**
     * Set HTTP headers before calling initialize().
     * Headers should be in CRLF format: "Header1: value1\r\nHeader2: value2\r\n"
     */
    fun setHttpHeaders(headers: String?) {
        this.httpHeaders = headers
    }

    /**
     * Initialize MPV. Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String, caFile: File) {
        if (isInitialized) {
            Log.w(TAG, "MPV already initialized")
            return
        }
        
        val fontsDir = File(context.filesDir, "fonts")

        session.create(context)
        try {

        // Set config options
        session.setOptionString("config", "yes")
        session.setOptionString("config-dir", configDir)
        
        // Cache directories
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            session.setOptionString(opt, cacheDir)
        
        // Font directory for libass - CRITICAL for text subtitle rendering
        session.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
        session.setOptionString("osd-fonts-dir", fontsDir.absolutePath)

        // Initialize options before session.init()
        initOptions()

        // Set HTTP headers if provided (must be before init)
        httpHeaders?.let { headers ->
            if (headers.isNotEmpty()) {
                session.setOptionString("http-header-fields", com.flex.elefin.player.mpv.toMpvHeaderOption(headers))
                Log.d(TAG, "HTTP headers set")
            }
        }

        // Disable ytdl to prevent interference with direct URLs
        session.setOptionString("ytdl", "no")
        session.setOptionString("load-scripts", "no")

        check(session.setOptionString("tls-verify", "yes") == 0) { "MPV 无法启用 HTTPS 证书校验" }
        check(session.setOptionString("tls-ca-file", caFile.absolutePath) == 0) { "MPV 无法加载可信证书" }
        session.init()

        // Post-init options
        postInitOptions()

        // Surface management options - keep window alive for subtitle rendering
        session.setOptionString("force-window", "yes")  // Keep window even without surface
        session.setOptionString("keep-open", "yes")  // Keep player open after playback ends
        session.setOptionString("idle", "yes")  // Stay idle instead of exiting

        holder.addCallback(this)
        observeProperties()
        
        isInitialized = true
        Log.d(TAG, "MPV initialized successfully")
        
        // Log subtitle-related properties for debugging
        val subVis = session.getPropertyBoolean("sub-visibility")
        val sid = session.getPropertyString("sid")
        Log.d(TAG, "Initial subtitle state: sub-visibility=$subVis, sid=$sid")
        } catch (error: Throwable) {
            holder.removeCallback(this)
            session.destroy()
            isInitialized = false
            throw error
        }
    }

    private fun initOptions() {
        // Use fast profile for mobile
        session.setOptionString("profile", "fast")

        // Video output - Initialize as null to prevent "Missing surface pointer" error
        // We will enable it in surfaceCreated
        session.setOptionString("vo", "null")

        // Hardware decoding
        session.setOptionString("hwdec", HWDECS)
        session.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        // Audio output
        session.setOptionString("ao", "audiotrack,opensles")
        
        // Subtitle settings - ensure subtitles are visible and rendered
        session.setOptionString("sub-visibility", "yes")
        session.setOptionString("sub-auto", "fuzzy")  // Auto-load external subtitles
        session.setOptionString("sid", "auto")  // Auto-select first subtitle track
        session.setOptionString("sub-forced-events-only", "no")  // Show all subtitle events, not just forced
        
        // Font settings - CRITICAL for subtitle rendering
        session.setOptionString("embeddedfonts", "yes")  // Use fonts embedded in video files
        session.setOptionString("sub-font", "sans-serif")
        session.setOptionString("sub-font-provider", "auto")  // System links also cover builds without a provider
        
        // Subtitle rendering - CRITICAL for Android GPU output
        session.setOptionString("sub-ass", "yes")  // Enable ASS/SSA subtitle rendering
        session.setOptionString("sub-ass-force-margins", "no")  // Don't force margins
        
        // Subtitle/video blending. "video" blends subtitles into the frame *before*
        // scaling, which forces an extra full-resolution GPU pass - expensive on the
        // Mali GPUs in Android 5 boxes. mpv's default draws them after scaling and
        // looks the same on a TV, so stay with the default.
        session.setOptionString("blend-subtitles", "no")
        
        // Secondary subtitle (for dual subtitle display) - disabled
        session.setOptionString("secondary-sid", "no")
        
        // Subtitle styling for SRT and other text subtitles  
        session.setOptionString("sub-font-size", "55")  // Larger font for TV visibility
        session.setOptionString("sub-color", "#FFFFFFFF")  // White text
        session.setOptionString("sub-border-color", "#FF000000")  // Black border
        session.setOptionString("sub-border-size", "3")  // Border thickness
        session.setOptionString("sub-shadow-color", "#80000000")  // Semi-transparent shadow
        session.setOptionString("sub-shadow-offset", "2")  // Shadow offset
        session.setOptionString("sub-pos", "95")  // Position from top (95% = near bottom)
        
        // Ensure subtitles are rendered
        session.setOptionString("sub-scale", "1.0")
        session.setOptionString("sub-scale-with-window", "yes")
        session.setOptionString("sub-use-margins", "yes")  // Use margins for positioning
        
        // OSD settings - required for subtitle display
        session.setOptionString("osd-level", "3")  // Full OSD including subtitles
        session.setOptionString("osd-bar", "yes")
        
        // Log level. Verbose ("all=v") formats every internal mpv message and pushes it
        // across JNI to the Java log callback - a per-packet/per-frame cost that is
        // clearly visible on weak Android TV SoCs. Keep errors only.
        session.setOptionString("msg-level", "all=error")

        // Display FPS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val disp = ContextCompat.getDisplayOrDefault(context)
            val refreshRate = disp.mode.refreshRate
            Log.v(TAG, "Display reports FPS of $refreshRate")
            session.setOptionString("display-fps-override", refreshRate.toString())
        }

        // GPU context for Android - CRITICAL for subtitle rendering
        session.setOptionString("gpu-context", "android")
        session.setOptionString("gpu-api", "opengl")  // Required for libass subtitle overlay
        session.setOptionString("opengl-es", "yes")


        // Demuxer cache settings for mobile
        val cacheMegs = if (context.hasTightMemory()) 16 else 32
        session.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        session.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024 / 4}")
    }

    private fun postInitOptions() {
        // Don't auto-save position, we handle this ourselves for Jellyfin
        session.setOptionString("save-position-on-quit", "no")
    }

    private fun observeProperties() {
        data class Property(val name: String, val format: Int = MPV_FORMAT_NONE)
        val properties = arrayOf(
            Property("time-pos", MPV_FORMAT_INT64),
            Property("duration/full", MPV_FORMAT_DOUBLE),
            Property("pause", MPV_FORMAT_FLAG),
            Property("paused-for-cache", MPV_FORMAT_FLAG),
            Property("speed", MPV_FORMAT_STRING),
            Property("track-list"),
            Property("video-params/aspect", MPV_FORMAT_DOUBLE),
            Property("playlist-pos", MPV_FORMAT_INT64),
            Property("playlist-count", MPV_FORMAT_INT64),
            Property("media-title", MPV_FORMAT_STRING),
            Property("hwdec-current"),
            Property("eof-reached", MPV_FORMAT_FLAG)
        )

        for ((name, format) in properties)
            session.observeProperty(name, format)
    }

    /**
     * Destroy MPV. Call this when done with playback.
     */
    fun destroy() {
        if (!isInitialized) return
        
        holder.removeCallback(this)
        session.destroy()
        isInitialized = false
        Log.d(TAG, "MPV destroyed")
    }

    /**
     * Load and play a file.
     */
    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    /**
     * Set the video output to use.
     */
    fun setVo(vo: String) {
        voInUse = vo
        session.setOptionString("vo", vo)
    }

    // SurfaceHolder.Callback implementation

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created, attaching to MPV")
        session.attachSurface(holder.surface)
        session.setOptionString("force-window", "yes")
        
        // Enable VO now that surface is ready
        session.setPropertyString("vo", voInUse)

        if (filePath != null) {
            session.command(arrayOf("loadfile", filePath as String))
            filePath = null
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed, detaching from MPV")
        session.setPropertyString("vo", "null")
        session.setPropertyString("force-window", "no")
        session.detachSurface()
    }

    // Observer management

    fun addObserver(o: MPVLib.EventObserver) {
        MPVLib.addObserver(o)
    }

    fun removeObserver(o: MPVLib.EventObserver) {
        MPVLib.removeObserver(o)
    }

    // Playback control properties

    var paused: Boolean?
        get() = session.getPropertyBoolean("pause")
        set(value) = session.setPropertyBoolean("pause", value!!)

    var timePos: Double?
        get() = session.getPropertyDouble("time-pos/full")
        set(value) = session.setPropertyDouble("time-pos", value!!)

    val duration: Double?
        get() = session.getPropertyDouble("duration/full")

    val hwdecActive: String
        get() = session.getPropertyString("hwdec-current") ?: "no"

    var playbackSpeed: Double?
        get() = session.getPropertyDouble("speed")
        set(value) = session.setPropertyDouble("speed", value!!)

    val eofReached: Boolean?
        get() = session.getPropertyBoolean("eof-reached")

    // Playback control methods

    fun cyclePause() = session.command(arrayOf("cycle", "pause"))
    
    fun pause() {
        paused = true
    }
    
    fun play() {
        paused = false
    }

    fun seek(seconds: Int) {
        session.command(arrayOf("seek", seconds.toString(), "relative"))
    }

    fun seekTo(position: Double) {
        timePos = position
    }

    fun cycleAudio() = session.command(arrayOf("cycle", "audio"))
    
    fun cycleSub() = session.command(arrayOf("cycle", "sub"))
    
    fun cycleHwdec() = session.command(arrayOf("cycle-values", "hwdec", HWDECS, "no"))

    fun cycleSpeed() {
        val speeds = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        val currentSpeed = playbackSpeed ?: 1.0
        val index = speeds.indexOfFirst { it > currentSpeed }
        playbackSpeed = speeds[if (index == -1) 0 else index]
    }

    // Track information

    data class Track(val mpvId: Int, val name: String, val lang: String? = null)
    
    @Volatile
    var tracks: Map<String, List<Track>> = emptyMap()
        private set

    fun loadTracks() {
        val loaded = listOf("audio", "video", "sub").associateWith { mutableListOf(Track(-1, "Off")) }
        val count = session.getPropertyInt("track-list/count") ?: return
        
        for (i in 0 until count) {
            val type = session.getPropertyString("track-list/$i/type") ?: continue
            if (!loaded.containsKey(type)) continue
            
            val mpvId = session.getPropertyInt("track-list/$i/id") ?: continue
            val lang = session.getPropertyString("track-list/$i/lang")
            val title = session.getPropertyString("track-list/$i/title")
            val codec = session.getPropertyString("track-list/$i/codec")

            // Build track name
            val trackName = when {
                !title.isNullOrEmpty() && !lang.isNullOrEmpty() -> "$title ($lang)"
                !title.isNullOrEmpty() -> title
                !lang.isNullOrEmpty() -> lang.uppercase()
                else -> "Track $mpvId"
            }
            
            loaded.getValue(type).add(Track(mpvId = mpvId, name = trackName, lang = lang))
            
            // Log subtitle codec info for debugging
            if (type == "sub") {
                Log.d(TAG, "Subtitle track $mpvId: $trackName, codec=$codec")
            }
        }
        
        tracks = loaded.mapValues { it.value.toList() }
        Log.d(TAG, "Loaded ${tracks["audio"]?.size ?: 0} audio tracks, ${tracks["sub"]?.size ?: 0} subtitle tracks")
    }

    // Track selection

    var vid: Int
        get() = session.getPropertyString("vid")?.toIntOrNull() ?: -1
        set(value) {
            if (value == -1) session.setPropertyString("vid", "no")
            else session.setPropertyInt("vid", value)
        }

    var sid: Int
        get() = session.getPropertyString("sid")?.toIntOrNull() ?: -1
        set(value) {
            if (value == -1) session.setPropertyString("sid", "no")
            else session.setPropertyInt("sid", value)
        }

    var aid: Int
        get() = session.getPropertyString("aid")?.toIntOrNull() ?: -1
        set(value) {
            if (value == -1) session.setPropertyString("aid", "no")
            else session.setPropertyInt("aid", value)
        }
}
