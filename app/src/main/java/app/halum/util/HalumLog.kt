package app.halum.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A 100%-copy-pasteable run log. Every [Log] call is mirrored into a fixed-size
 * ring buffer with a timestamp and level, so a user can tap "Copy log" and hand
 * back exactly what happened — no logcat, no adb. Pure JVM: no Android types, so
 * [dump] and the ring behave the same in unit tests.
 *
 * Installed by `HalumApp` as [Log]'s sink (alongside logcat); the copy button
 * calls [dump].
 */
object HalumLog : Log.Sink {
    private const val CAPACITY = 2_000

    /** Timestamps are formatted under the ring lock — SimpleDateFormat is not thread-safe. */
    private val ring = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var lastAndroid: Log.Sink? = null

    /** Chain to the logcat sink so both see the line. */
    fun install(android: Log.Sink) {
        lastAndroid = android
        Log.sink = this
    }

    override fun write(
        priority: Int,
        tag: String,
        message: String,
    ) {
        synchronized(ring) {
            if (ring.size == CAPACITY) ring.removeFirst()
            ring.addLast("${fmt.format(Date())} ${level(priority)} $tag: $message")
        }
        lastAndroid?.write(priority, tag, message)
    }

    /** Everything recorded, oldest first, one line each. Safe to paste. */
    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }

    fun clear() = synchronized(ring) { ring.clear() }

    /** Count of error lines, for a quick "is something wrong". */
    fun errorCount(): Int = synchronized(ring) { ring.count { it.contains(" [E] ") } }

    private fun level(priority: Int): String =
        when {
            priority >= 6 -> "[E]"
            priority == 5 -> "[W]"
            priority == 4 -> "[I]"
            else -> "[D]"
        }
}
