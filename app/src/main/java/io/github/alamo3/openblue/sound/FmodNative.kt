package io.github.alamo3.openblue.sound

import android.content.Context

/**
 * The FMOD Studio runtime (FMOD Studio by Firelight Technologies Pty Ltd.), through the small
 * JNI bridge in src/main/cpp/fmod_bridge.cpp. Handles are opaque longs; 0 means failure.
 */
object FmodNative {
    const val TAG = "OpenBlueFmod"
    @Volatile private var loaded: Boolean? = null
    /** Why FMOD couldn't start, for the screen; null when fine or not tried. */
    @Volatile var error: String? = null
        private set

    /**
     * Loads the native libraries and initialises FMOD's Android layer; false if unavailable.
     * Call it on the main thread first (FMOD's Java side sets itself up with the app context
     * there); later calls from other threads just return the result.
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("fmod")
            System.loadLibrary("fmodstudio")
            System.loadLibrary("openblue_fmod")
            org.fmod.FMOD.init(context.applicationContext)
            android.util.Log.i(TAG, "FMOD loaded")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "FMOD failed to load", t)
            error = "FMOD failed to load: ${t.javaClass.simpleName}: ${t.message}"
            false
        }
        loaded = ok
        return ok
    }

    external fun create(blockFrames: Int): Long
    external fun release(sys: Long)
    external fun update(sys: Long): Boolean
    external fun loadBank(sys: Long, path: String): Long
    external fun event(sys: Long, guid: String): Long
    external fun instance(desc: Long): Long
    external fun start(inst: Long)
    external fun stop(inst: Long, immediate: Boolean)
    external fun releaseInstance(inst: Long)
    external fun setParam(inst: Long, name: String, value: Float)
    external fun oneShot(desc: Long, name: String?, value: Float)
    external fun setBusVolume(sys: Long, guid: String, volume: Float)
    external fun setMasterVolume(sys: Long, volume: Float)
}
