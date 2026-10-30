package app.halum

import android.app.Application
import app.halum.util.HalumLog
import app.halum.util.Log

class HalumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the recorder before the first log line so the ring buffer
        // holds the whole run. It chains to logcat, it does not replace it.
        HalumLog.install(Log.androidSink)
        Log.i(TAG, "Halum ${BuildConfig.VERSION_NAME} starting, capture=${BuildConfig.HALUM_CAPTURE}")
        // OpenCV is a 30 MB native payload; loading it here would cost start-up
        // time for users who never trigger the pipeline. InpaintEngine loads it
        // on first use instead.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "TRIM_MEMORY: level=$level")
    }

    private companion object {
        const val TAG = "HALUM_APP"
    }
}
