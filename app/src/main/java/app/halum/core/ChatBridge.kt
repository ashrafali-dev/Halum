package app.halum.core

/**
 * D14.11: the chatbot bridge lives in a plain class over [ChatPage] so all
 * states, timeouts and cancellation are unit-tested in pure JVM.
 *
 * No Thread.sleep: [Clock] is the only notion of time, so tests drive the
 * whole machine deterministically instead of waiting on real timers.
 *
 * Flow: IDLE → WAITING_READY → SENDING → AWAITING_REPLY → DONE
 *                                                 ↘ TIMED_OUT / FAILED
 * A partial reply is retried as a whole batch; when the batch keeps failing
 * the missing blocks are sent one per message, max [MAX_RETRIES] per block (D6).
 */
class ChatBridge(
    private val page: ChatPage,
    private val clock: Clock = SystemClock,
) {
    interface ChatPage {
        fun isReady(): Boolean

        fun inject(text: String)

        /** Most recent assistant message text, or null if none yet. */
        fun lastReply(): String?

        fun newConversation()
    }

    interface Clock {
        fun nowMillis(): Long

        /** Advance simulated time. Production blocks; tests pass a no-op. */
        fun sleep(millis: Long)
    }

    object SystemClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun sleep(millis: Long) = Thread.sleep(millis)
    }

    enum class State {
        IDLE,
        WAITING_READY,
        SENDING,
        AWAITING_REPLY,
        DONE,
        TIMED_OUT,
        FAILED,
        CANCELLED,
    }

    data class Outcome(
        val state: State,
        val translations: List<String?> = emptyList(),
        val missing: List<Int> = emptyList(),
        val attempts: Int = 0,
    )

    companion object {
        const val MAX_RETRIES = 3
        const val READY_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 250L
        const val MAX_BARE_CHARS = 500

        private val REFUSAL_MARKERS =
            listOf(
                "i'm sorry",
                "i am sorry",
                "i can't",
                "i cannot",
                "as an ai",
                "sorry, i",
                "unable to",
            )
    }

    var state: State = State.IDLE
        private set

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun translate(blocks: List<String>): Outcome {
        if (blocks.isEmpty()) return finish(State.DONE)
        cancelled = false
        state = State.WAITING_READY
        if (!await(page::isReady, READY_TIMEOUT_MS)) {
            return finish(if (cancelled) State.CANCELLED else State.TIMED_OUT)
        }

        val results = arrayOfNulls<String>(blocks.size)
        var attempts = 0
        for (batch in BatchPlanner.plan(blocks)) {
            val batchTexts = batch.indices.map { blocks[it] }
            var batchOutcome: List<String?>? = null
            for (tryNo in 0 until MAX_RETRIES) {
                if (cancelled) return finish(State.CANCELLED, results.asList(), attempts)
                attempts++
                val reply = sendAwait(PromptBuilder.build(batchTexts))
                if (reply == null) {
                    if (cancelled) return finish(State.CANCELLED, results.asList(), attempts)
                    break // timeout: do not hammer a silent chatbot
                }
                val parsed = ResponseParser.parse(reply, batchTexts.size)
                if (parsed.complete) {
                    batchOutcome = parsed.translations
                    break
                }
            }
            // D6: a batch that answered nothing (timeout) or only partly is
            // retried one block per send; the batch verdict is kept otherwise.
            if (batchOutcome == null) {
                batchOutcome = retryIndividually(batchTexts)
                attempts += retryAttempts
            }
            batch.indices.forEachIndexed { k, globalIndex -> results[globalIndex] = batchOutcome[k] }
        }
        val missing = results.indices.filter { results[it] == null }
        return finish(
            if (missing.isEmpty()) State.DONE else State.FAILED,
            results.asList(),
            attempts,
            missing,
        )
    }

    /** Sends counted by [retryIndividually], included in the outcome's attempts. */
    private var retryAttempts = 0

    private fun retryIndividually(batchTexts: List<String>): List<String?> {
        retryAttempts = 0
        val out = arrayOfNulls<String>(batchTexts.size)
        for (i in batchTexts.indices) {
            repeat(MAX_RETRIES) {
                if (cancelled || out[i] != null) return@repeat
                retryAttempts++
                val reply = sendAwait(PromptBuilder.build(listOf(batchTexts[i]))) ?: return@repeat
                // A one-block reply may come back as bare prose. ResponseParser
                // would accept any single line positionally, including a chat
                // refusal, so it is only used when it actually found a marker.
                val parsed = ResponseParser.parse(reply, 1)
                out[i] =
                    if (parsed.mode == ResponseParser.Mode.MARKERS) {
                        parsed.translations.firstOrNull()
                    } else {
                        bareText(reply)
                    }
            }
        }
        return out.asList()
    }

    /**
     * A one-block reply that came back as prose. Short refusals ("as an AI…")
     * are never placed into the artwork — that would print chatbot chatter on
     * the page — so only plausible translations are accepted.
     */
    private fun bareText(reply: String): String? {
        val text = reply.trim()
        if (text.isEmpty() || text.length > MAX_BARE_CHARS) return null
        // Refusals must not be painted on the artwork (see REFUSAL_MARKERS).
        val lower = text.lowercase()
        if (REFUSAL_MARKERS.any { lower.contains(it) }) return null
        return text
    }

    /** Returns null on timeout/cancel; the caller decides what to retry. */
    private fun sendAwait(prompt: String): String? {
        state = State.SENDING
        page.newConversation()
        val previous = page.lastReply()
        page.inject(prompt)
        state = State.AWAITING_REPLY
        val ok =
            await(
                {
                    val reply = page.lastReply()
                    reply != null && reply != previous && reply.isNotBlank()
                },
                REPLY_TIMEOUT_MS,
            )
        if (!ok) {
            state = if (cancelled) State.CANCELLED else State.TIMED_OUT
            return null
        }
        return page.lastReply()
    }

    private fun await(
        predicate: () -> Boolean,
        timeoutMs: Long,
    ): Boolean {
        val deadline = clock.nowMillis() + timeoutMs
        while (true) {
            if (cancelled) return false
            if (predicate()) return true
            if (clock.nowMillis() >= deadline) return false
            clock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun finish(
        s: State,
        translations: List<String?> = emptyList(),
        attempts: Int = 0,
        missing: List<Int> = emptyList(),
    ): Outcome {
        state = s
        return Outcome(s, translations, missing, attempts)
    }
}
