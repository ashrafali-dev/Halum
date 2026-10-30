package app.halum.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D18: replays every scripted session in `fixtures/chatbot/sessions.json`
 * through [ChatBridge] and asserts the outcome the fixture records.
 *
 * This is the bridge's verification evidence for AC5. It drives the same
 * classes the app uses (ChatBridge + its ChatPage interface) with a page that
 * answers a fixed reply, which is enough to pin the state machine, the parser
 * integration and the refusal filter. The streaming and partial-batch cases
 * need a per-prompt script and live in ChatBridgeTest.
 */
class ChatBridgeVerificationTest {
    /** A page whose single answer is [reply] (null = never answers). */
    private class FixedReplyPage(
        private val reply: String?,
    ) : ChatBridge.ChatPage {
        var injected = mutableListOf<String>()

        override fun isReady() = true

        override fun inject(text: String) {
            injected += text
        }

        /** The reply is visible as soon as a prompt has been injected. */
        override fun lastReply(): String? = if (injected.isEmpty()) null else reply

        override fun newConversation() = Unit
    }

    /** Advances only on sleep(), so a timeout resolves without real waiting. */
    private class TickingClock : ChatBridge.Clock {
        var now = 0L

        override fun nowMillis() = now

        override fun sleep(millis: Long) {
            now += millis
        }
    }

    private fun fixture(): JSONObject {
        val dir = System.getProperty("halum.fixtures") ?: error("halum.fixtures not set by Gradle")
        val file = File(dir, "chatbot/sessions.json")
        require(file.isFile) { "missing fixture ${file.absolutePath}" }
        return JSONObject(file.readText())
    }

    @Test
    fun `every scripted session reproduces its recorded outcome`() {
        val sessions = fixture().getJSONArray("sessions")
        assertTrue("no sessions in fixture", sessions.length() > 0)
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val name = s.getString("name")
            val blocks = s.getJSONArray("blocks").let { a -> List(a.length()) { a.getString(it) } }
            val reply = if (s.isNull("reply")) null else s.getString("reply")
            val expect = s.getJSONObject("expect")

            val page = FixedReplyPage(reply)
            val bridge = ChatBridge(page, TickingClock())

            val out = bridge.translate(blocks)

            val expectedState = ChatBridge.State.valueOf(expect.getString("state"))
            assertEquals("$name: outcome state", expectedState, out.state)
            assertEquals("$name: bridge state", expectedState, bridge.state)
            val expectedMissing =
                expect.getJSONArray("missing").let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals("$name: missing", expectedMissing, out.missing)
            // A refusal retries (batch + individual), so only the answered
            // sessions are guaranteed a single send.
            if (expectedState == ChatBridge.State.DONE) {
                assertEquals("$name: one send", 1, page.injected.size)
            }
        }
    }

    @Test
    fun `cancel mid-stream stops sending`() {
        lateinit var bridge: ChatBridge
        val page =
            object : ChatBridge.ChatPage {
                override fun isReady() = true

                override fun inject(text: String) {
                    bridge.cancel()
                }

                override fun lastReply(): String? = null

                override fun newConversation() = Unit
            }
        bridge = ChatBridge(page, TickingClock())
        val out = bridge.translate(listOf("one"))
        assertEquals(ChatBridge.State.CANCELLED, out.state)
    }
}
