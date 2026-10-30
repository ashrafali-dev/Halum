package app.halum.engine

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.halum.core.ChatBridge
import app.halum.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * D14.11: [ChatBridge.ChatPage] over the CHAT WebView, with all DOM knowledge
 * in `assets/js/chat_bridge.js`.
 *
 * The bridge is a synchronous state machine driven by a fake clock, so every
 * call here blocks the caller until the WebView answers. Callers run the
 * bridge on a worker thread — never on the UI thread.
 */
class WebViewChatPage(
    private val webView: WebView,
    private val script: String,
    private val timeoutMs: Long = 5_000,
    /** Q1: told once when the chat UI could not be recognised. */
    private val onBridgeFail: ((reason: String) -> Unit)? = null,
) : ChatBridge.ChatPage {
    private val ready = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())

    /** Results come back from JS through this object, on a JS thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onResult(
            id: Int,
            value: String?,
        ) {
            pending.remove(id)?.let { it(value) }
        }
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<Int, (String?) -> Unit>()
    private val nextId =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    init {
        // Constructed on the pipeline worker, but every WebView call must be on
        // the thread that owns it. Post the setup rather than touching it here.
        runOnWebViewThread {
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(Bridge(), JS_NAME)
            // Install once the page is there; an SPA replaces the DOM after
            // load, so the script re-defines window.halum harmlessly.
            webView.evaluateJavascript(script, null)
            ready.countDown()
        }
    }

    /** Runs [block] on the UI thread, or inline when already there. */
    private fun runOnWebViewThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    override fun isReady(): Boolean = await("!!(window.halum && window.halum.ready())") == "true"

    override fun inject(text: String) {
        val quoted = org.json.JSONObject.quote(text)
        val raw = await("window.halum.inject($quoted)") ?: return
        // Q1: the script returns {ok,inputFound,submitFound,adapter}; log the
        // adapter choice and a precise failure reason instead of silently
        // no-op'ing when the chat UI was not recognised.
        val parsed = runCatching { org.json.JSONTokener(raw).nextValue() as? org.json.JSONObject }.getOrNull()
        val o = parsed ?: return
        val adapter = o.optString("adapter", "none")
        if (o.optBoolean("ok")) {
            // url is a WebView getter: capture it on the UI thread, log here.
            val host = AtomicReference("?")
            runOnWebViewThread { host.set(webView.url ?: "?") }
            Log.i(TAG, "BRIDGE_ADAPTER: host=${host.get()} adapter=$adapter")
        } else {
            val reason = o.optString("reason", "unknown")
            Log.w(TAG, "BRIDGE_FAIL: reason=$reason adapter=$adapter")
            onBridgeFail?.invoke(reason)
        }
    }

    override fun lastReply(): String? {
        val raw = await("window.halum.lastReply()") ?: return null
        // `await` hands back the JS value already JSON-encoded: null, or a
        // quoted string. Anything else means the page changed under us.
        if (raw == "null") return null
        return runCatching {
            val decoded = org.json.JSONTokener(raw).nextValue()
            (decoded as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun newConversation() {
        await("window.halum.newConversation()")
    }

    private fun await(expression: String): String? {
        // The JS interface is installed on the UI thread; do not evaluate
        // before it exists or onResult has nowhere to land.
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "bridge not installed within ${timeoutMs}ms")
            return null
        }
        val id = nextId.incrementAndGet()
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        pending[id] = { v ->
            result.set(v)
            latch.countDown()
        }
        main.post {
            // The script is re-evaluated defensively: SPAs wipe injected globals
            // on navigation, and a missing window.halum would silently no-op.
            val js =
                """
                $script
                ;(function () {
                  try {
                    var v = ($expression);
                    $JS_NAME.onResult($id, JSON.stringify(v));
                  } catch (e) {
                    $JS_NAME.onResult($id, JSON.stringify(null));
                  }
                })();
                """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            Log.w(TAG, "js timeout: $expression")
            return null
        }
        return result.get()
    }

    private companion object {
        const val TAG = "WebViewChatPage"
        const val JS_NAME = "HalumBridge"
    }
}
