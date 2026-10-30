package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic fake page + clock; no real sleeping, no WebView (D14.11).
 *
 * [script] returns the reply for a prompt, or null to simulate a chatbot that
 * never answers. Replies become visible on the next [lastReply] call, which
 * mirrors "the DOM updated after the send".
 */
private class FakePage(
    private val script: (String) -> String?,
    var readyAfterPolls: Int = 0,
) : ChatBridge.ChatPage {
    var polls = 0
    var injected = mutableListOf<String>()
    private var reply: String? = null
    private var pending: String? = null

    override fun isReady(): Boolean {
        polls++
        return polls > readyAfterPolls
    }

    override fun inject(text: String) {
        injected += text
        pending = script(text)
    }

    override fun lastReply(): String? {
        pending?.let {
            reply = it
            pending = null
        }
        return reply
    }

    override fun newConversation() {
        reply = null
        pending = null
    }
}

private class FakeClock : ChatBridge.Clock {
    var now = 0L

    override fun nowMillis(): Long = now

    override fun sleep(millis: Long) {
        now += millis
    }
}

class ChatBridgeTest {
    private fun bridge(
        page: ChatBridge.ChatPage,
        clock: ChatBridge.Clock = FakeClock(),
    ) = ChatBridge(page, clock)

    @Test
    fun `happy path translates every block`() {
        val page = FakePage({ "[1] এক\n[2] দুই" })
        val out = bridge(page).translate(listOf("one", "two"))
        assertEquals(ChatBridge.State.DONE, out.state)
        assertEquals(listOf("এক", "দুই"), out.translations)
        assertTrue(out.missing.isEmpty())
    }

    @Test
    fun `waits for the page to become ready`() {
        val page = FakePage({ "[1] এক" }, readyAfterPolls = 2)
        val out = bridge(page).translate(listOf("one"))
        assertEquals(ChatBridge.State.DONE, out.state)
        assertEquals(1, page.injected.size)
    }

    @Test
    fun `never-ready page times out instead of injecting`() {
        val page = object : ChatBridge.ChatPage {
            override fun isReady() = false

            override fun inject(text: String) = error("must not inject before ready")

            override fun lastReply(): String? = null

            override fun newConversation() = Unit
        }
        val out = bridge(page).translate(listOf("one"))
        assertEquals(ChatBridge.State.TIMED_OUT, out.state)
    }

    @Test
    fun `useless chatbot retries then fails honestly`() {
        // One block, so the per-block fallback is the same single send; the
        // batch ladder gets MAX_RETRIES tries before it gives up.
        val page = FakePage({ "I'm sorry, I can't help with that." })
        val out = bridge(page).translate(listOf("one"))
        assertEquals(ChatBridge.State.FAILED, out.state)
        // 3 batch sends + 3 single-block fallback sends (1 block here).
        assertEquals(ChatBridge.MAX_RETRIES + ChatBridge.MAX_RETRIES, out.attempts)
        assertEquals(listOf(0), out.missing)
    }

    @Test
    fun `partial reply falls back to one block per send`() {
        val page =
            FakePage({ prompt ->
                when {
                    prompt.contains("[1] one") && prompt.contains("[2] two") -> "[1] এক" // 2 missing
                    prompt.contains("[2] two") -> "[2] দুই"
                    prompt.contains("[1] one") -> "[1] এক"
                    else -> null
                }
            })
        val out = bridge(page).translate(listOf("one", "two"))
        assertEquals(ChatBridge.State.DONE, out.state)
        assertEquals(listOf("এক", "দুই"), out.translations)
        assertTrue("batch retries plus the single-block fallback", page.injected.size >= 4)
    }

    @Test
    fun `cancellation is observed while awaiting the reply`() {
        val clock = FakeClock()
        lateinit var b: ChatBridge
        val page = object : ChatBridge.ChatPage {
            override fun isReady() = true

            override fun inject(text: String) {
                b.cancel()
            }

            override fun lastReply(): String? = null

            override fun newConversation() = Unit
        }
        b = ChatBridge(page, clock)
        val out = b.translate(listOf("one"))
        assertEquals(ChatBridge.State.CANCELLED, out.state)
    }

    @Test
    fun `multiple batches are sent separately and keep order`() {
        val blocks = List(10) { "b$it" }
        val page =
            FakePage({ prompt ->
                val count = Regex("""^\[(\d+)]""", RegexOption.MULTILINE).findAll(prompt).count()
                (1..count).joinToString("\n") { "[$it] t$it" }
            })
        val out = bridge(page).translate(blocks)
        assertEquals(ChatBridge.State.DONE, out.state)
        assertEquals(2, page.injected.size) // 8 + 2
        assertEquals(
            listOf("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t1", "t2"),
            out.translations,
        )
    }

    @Test
    fun `bare single-block reply is accepted`() {
        val page = FakePage({ "দুই" })
        val out = bridge(page).translate(listOf("two"))
        assertEquals(ChatBridge.State.DONE, out.state)
        assertEquals(listOf("দুই"), out.translations)
    }
}
