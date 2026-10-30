package app.halum.core.lens

import app.halum.core.LensProtocol

/**
 * D5: the Lens client never talks to the network directly. CI uses
 * [ReplayTransport] over committed fixtures; the app uses [HttpTransport].
 *
 * Keeping this seam in the core package means the rate limit, retry policy and
 * decode path are all unit-testable without a socket.
 */
interface LensTransport {
    /** Raw `LensOverlayServerResponse` bytes. Throws [TransportException]. */
    fun post(bytes: ByteArray): ByteArray

    class TransportException(
        message: String,
        val status: Int = 0,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)
}

/** Replays committed fixtures; what CI always uses (D2). */
class ReplayTransport(
    private val fixtures: List<ByteArray>,
) : LensTransport {
    private var next = 0

    override fun post(bytes: ByteArray): ByteArray {
        if (fixtures.isEmpty()) throw LensTransport.TransportException("no replay fixtures")
        return fixtures[next++ % fixtures.size]
    }
}

/**
 * Rate limit shared by both transports: at most [perMinute] calls in any
 * 60 s window, checked against an injected clock so tests never sleep.
 */
class RateLimiter(
    private val perMinute: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val calls = ArrayDeque<Long>()

    @Synchronized
    fun acquire() {
        val now = clock()
        while (calls.isNotEmpty() && now - calls.first() >= WINDOW_MS) calls.removeFirst()
        if (calls.size >= perMinute) {
            val wait = WINDOW_MS - (now - calls.first())
            throw LensProtocol.LensException("rate limited; retry in ${wait}ms")
        }
        calls.addLast(now)
    }

    @Synchronized
    fun used(): Int = calls.size

    companion object {
        const val WINDOW_MS = 60_000L
    }
}

/**
 * D5 at the transport level: decode failures and HTTP errors are surfaced,
 * never swallowed. Retries are limited to idempotent transport errors.
 */
class LensOcrClient(
    private val transport: LensTransport,
    private val limiter: RateLimiter = RateLimiter(),
    private val maxAttempts: Int = 2,
) {
    fun recognize(
        request: LensProtocol.Request,
        imageWidth: Int,
        imageHeight: Int,
    ): List<app.halum.core.OcrBlock> {
        val body = request.encode()
        var last: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                limiter.acquire()
                val response = transport.post(body)
                return LensProtocol.decode(response, imageWidth, imageHeight)
            } catch (e: LensTransport.TransportException) {
                last = e
                // A 4xx other than 429 will not get better; do not hammer it.
                if (e.status in 400..499 && e.status != 429) throw e
                if (attempt == maxAttempts - 1) throw e
            }
        }
        throw (last as? RuntimeException) ?: LensTransport.TransportException("lens request failed")
    }
}
