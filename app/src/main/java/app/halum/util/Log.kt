package app.halum.util

/**
 * Thin wrapper over android.util.Log so every tagged line in the spec
 * (`LENS_OCR:`, `BUBBLE_DETECT:`, `INPAINT:`, `BRIDGE_*`, `OVERLAY_*`) has one
 * formatting path, and so pure-JVM tests can substitute it.
 */
object Log {

    interface Sink {
        fun write(priority: Int, tag: String, message: String)
    }

    @Volatile
    var sink: Sink = AndroidSink

    fun d(tag: String, message: String) = sink.write(3, tag, message)

    fun i(tag: String, message: String) = sink.write(4, tag, message)

    fun w(tag: String, message: String) = sink.write(5, tag, message)

    fun e(tag: String, message: String, tr: Throwable? = null) {
        sink.write(6, tag, if (tr == null) message else "$message :: ${tr.javaClass.simpleName}: ${tr.message}")
    }

    /** Only android.util.Log touches Binder; JVM tests redirect via [sink]. */
    private object AndroidSink : Sink {
        override fun write(priority: Int, tag: String, message: String) {
            android.util.Log.println(priority, tag, message)
        }
    }
}
}
