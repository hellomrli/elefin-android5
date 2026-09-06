package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * BaseMPVView - Contains only the essential code needed to get a picture on the screen.
 * From mpv-android source code.
 */
abstract class BaseMPVView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    
    protected val session = MpvSession()

    companion object {
        private const val TAG = "mpv"
    }
    
    /**
     * Initialize libmpv.
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        session.create(context)

        // Set normal options (user-supplied config can override)
        session.setOptionString("config", "yes")
        session.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            session.setOptionString(opt, cacheDir)
        initOptions()

        session.init()

        // Set hardcoded options
        postInitOptions()
        // Could mess up VO init before surfaceCreated() is called
        session.setOptionString("force-window", "no")
        // Need to idle at least once for playFile() logic to work
        session.setOptionString("idle", "once")

        holder.addCallback(this)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)
        session.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    private var filePath: String? = null

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        session.setOptionString("vo", vo)
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        session.attachSurface(holder.surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        session.setOptionString("force-window", "yes")

        if (filePath != null) {
            session.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            session.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        session.setPropertyString("vo", "null")
        session.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        session.detachSurface()
    }
}

