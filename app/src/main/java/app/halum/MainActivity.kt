package app.halum

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.halum.core.ChatBridge
import app.halum.databinding.ActivityMainBinding
import app.halum.engine.AdBlockWebViewClient
import app.halum.engine.AndroidPanelPipeline
import app.halum.engine.BubbleDetector
import app.halum.engine.HalumPrefs
import app.halum.engine.LensPort
import app.halum.engine.WebViewChatPage
import app.halum.model.Models
import app.halum.overlay.OverlayView
import app.halum.util.HalumLog
import app.halum.util.Log
import java.util.concurrent.Executors

/**
 * Two WebViews (MAIN = manga, CHAT = chatbot) with a draggable splitter.
 * Pipeline wiring arrives in Phase 3; this activity owns view lifecycle only.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: HalumPrefs

    private var dragging = false
    private var dragStartY = 0f
    private var dragStartWeight = 0.5f

    /** Single worker: the pipeline blocks on the WebView bridge, never on UI. */
    private val worker = Executors.newSingleThreadExecutor()
    private val bridgeScript: String by lazy {
        assets.open("js/chat_bridge.js").bufferedReader().use { it.readText() }
    }
    private var pipeline: AndroidPanelPipeline? = null
    private var lastOverlayScrollY = 0

    /** One pipeline run at a time; a second tap is ignored, not queued. */
    private val busy =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** True between Start and Stop; scrolling triggers work only in this state. */
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = HalumPrefs(this)

        configureWebView(binding.mainWebView, MAIN_UA_SUFFIX)
        configureWebView(binding.chatWebView, CHAT_UA_SUFFIX)

        binding.chatWebView.loadUrl(CHAT_HOME)
        binding.mainWebView.loadUrl(START_URL)

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            setMode(if (checkedId == R.id.btnScan) Mode.SCAN else Mode.REALTIME)
        }
        binding.btnUrl.setOnClickListener { openUrlPrompt() }

        binding.splitter.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartY = event.rawY
                    dragStartWeight = weightOf(binding.mainContainer)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dy = event.rawY - dragStartY
                        val total =
                            (binding.mainContainer.height + binding.chatContainer.height)
                                .coerceAtLeast(1)
                        val newWeight = (dragStartWeight + dy / total).coerceIn(0.15f, 0.85f)
                        setWeights(newWeight)
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }

        setMode(Mode.SCAN)
        wirePipeline()
    }

    /**
     * D19 Step 4: connect the verified engines to the UI. The pipeline is
     * built lazily per run so a changed chat URL or model is picked up.
     */
    private fun wirePipeline() {
        binding.btnTranslate.setOnClickListener { translateVisiblePanel() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnCopyLog.setOnClickListener { copyLog() }
        binding.btnStartStop.setOnClickListener { toggleRunning() }

        // D9: user scroll dismisses the overlay; a pipeline transition must not.
        binding.mainWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY != lastOverlayScrollY) {
                lastOverlayScrollY = scrollY
                binding.overlay.clear(OverlayView.ClearReason.SCROLL)
                // Realtime follows the viewport: a scroll makes the old panel
                // stale, so translate what is visible now. Scan does not — it
                // walks the page on an explicit Start.
                if (running && currentMode == Mode.REALTIME) translateVisiblePanel()
            }
        }
        // Q2 level 3: long-press a translation to edit it. The overlay is not
        // touchable (it must not steal scrolling), so the gesture is detected
        // on the WebView and hit-tested against the overlay placements.
        val gest =
            android.view.GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onLongPress(e: android.view.MotionEvent) {
                        lastTouchX = e.x
                        lastTouchY = e.y
                        openBubbleEditor()
                    }
                },
            )
        binding.mainWebView.setOnTouchListener { v, ev ->
            if (!binding.overlay.isEmpty) gest.onTouchEvent(ev)
            false // never consume; the WebView must still scroll
        }
    }

    private fun buildPipeline(): AndroidPanelPipeline {
        val page =
            WebViewChatPage(
                webView = binding.chatWebView,
                script = bridgeScript,
                onBridgeFail = {
                    runOnUiThread {
                        Toast.makeText(this, R.string.bridge_not_recognized, Toast.LENGTH_LONG).show()
                    }
                },
            )
        val bridge = ChatBridge(page)
        val detector = BubbleDetector.create(Models.store(this))
        val lens = LensPort.http(cookies = { CookieManager.getInstance().getCookie(LensPort.HOST) }, store = {})
        return AndroidPanelPipeline(lens, detector, bridge)
    }

    /**
     * Captures the visible MAIN panel and runs the pipeline.
     *
     * Building the pipeline copies a 5.86 MB model out of assets, hashes it,
     * mmaps it and instantiates a TFLite interpreter — on a 192 MB heap that
     * is an OOM if it happens on the UI thread (and it did: the app died on
     * the first tap, before OCR). Building and running both happen on [worker].
     */
    private fun translateVisiblePanel() {
        if (!busy.compareAndSet(false, true)) return
        setProgress(0, getString(R.string.progress_working))
        captureOnUiThread(
            onDone = { bitmap ->
                setProgress(0, getString(R.string.progress_ocr))
                worker.execute {
                    if (bitmap == null) {
                        finishRun { setProgress(0, getString(R.string.error_capture_failed)) }
                        return@execute
                    }
                    // From here on the bitmap is owned by the worker.
                    val outcome =
                        runCatching {
                            val p = pipeline ?: buildPipeline().also { pipeline = it }
                            p.process(bitmap)
                        }.onFailure { Log.e(TAG, "PIPE_FAILED", it) }
                    bitmap.recycle()
                    finishRun { deliver(outcome.getOrNull()) }
                }
            },
            onFailed = { finishRun { setProgress(0, getString(R.string.error_capture_failed)) } },
        )
    }

    /**
     * The WebView can only be drawn on the UI thread; PixelCopy-free `draw`
     * is fine there, it is the model work that must leave it.
     */
    private fun captureOnUiThread(
        onDone: (Bitmap?) -> Unit,
        onFailed: () -> Unit,
    ) {
        binding.mainWebView.post {
            if (binding.mainWebView.width <= 0 || binding.mainWebView.height <= 0) {
                onFailed()
                return@post
            }
            onDone(captureView(binding.mainWebView))
        }
    }

    private fun finishRun(then: () -> Unit) {
        runOnUiThread {
            try {
                then()
            } finally {
                busy.set(false)
            }
        }
    }

    private fun deliver(outcome: AndroidPanelPipeline.PanelOutcome?) {
        if (outcome == null) {
            setProgress(0, getString(R.string.error_ocr_failed))
            return
        }
        // Boxes are in captured-bitmap pixels; the overlay draws in view pixels.
        val sx = if (outcome.width > 0) binding.mainWebView.width.toFloat() / outcome.width else 1f
        val sy = if (outcome.height > 0) binding.mainWebView.height.toFloat() / outcome.height else 1f
        val placements =
            outcome.placements.map {
                OverlayView.Placement(
                    app.halum.core.BubbleClassifier.Box(
                        it.box.x1 * sx,
                        it.box.y1 * sy,
                        it.box.x2 * sx,
                        it.box.y2 * sy,
                    ),
                    it.text,
                    it.isSmall,
                )
            }
        binding.overlay.show(placements, binding.mainWebView.scrollY)
        setProgress(100, getString(R.string.progress_panel, outcome.placements.size, outcome.bubbles))
        Log.i(TAG, "OVERLAY_PLACED: count=${placements.size} scale=${"%.2f".format(sx)}")
    }

    /**
     * Draws the WebView into a bitmap, downsampled so the long edge is at most
     * [MAX_CAPTURE_EDGE]. Lens resizes to 640 anyway, and a full ARGB_8888
     * screen grab (1080x2400 = 10.4 MB) plus per-bubble page-sized masks is
     * what pushes this app over a 192 MB heap.
     */
    private fun captureView(view: View): Bitmap {
        val w = view.width
        val h = view.height
        val longEdge = maxOf(w, h)
        val scale = if (longEdge > MAX_CAPTURE_EDGE) MAX_CAPTURE_EDGE.toFloat() / longEdge else 1f
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale, scale)
        view.draw(canvas)
        return bmp
    }

    private fun openSettings() {
        val input =
            EditText(this).apply {
                setText(prefs.globalPrompt())
                setSingleLine(false)
                minLines = 4
                gravity = android.view.Gravity.TOP
            }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.settings_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.saveGlobalPrompt(input.text.toString())
            }.setNeutralButton(R.string.settings_prompt_reset) { _, _ ->
                prefs.resetGlobalPrompt()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Copies the in-app ring buffer so the user can paste it back to us. */
    private fun copyLog() {
        val text = HalumLog.dump()
        val lines = text.lines().count { it.isNotBlank() }
        if (lines == 0) {
            Toast.makeText(this, R.string.settings_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(LOG_CLIP_LABEL, text))
        Log.i(TAG, "LOG_COPIED: lines=$lines")
        Toast.makeText(this, getString(R.string.settings_log_copied, lines), Toast.LENGTH_SHORT).show()
    }

    private fun openBubbleEditor() {
        // Q2 level 3: the overlay reports which placement was hit on long-press.
        val hit = binding.overlay.hitAt(lastTouchX, lastTouchY) ?: return
        val input = EditText(this).apply { setText(hit.text) }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.action_edit_translation)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newText = input.text.toString()
                binding.overlay.editAt(hit.index, newText)
                prefs.saveBubbleEdit(
                    currentChapterId(),
                    binding.mainWebView.scrollY,
                    hit.index,
                    newText,
                    hit.text.length,
                )
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentChapterId(): String = binding.mainWebView.url?.take(200) ?: "unknown"

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchX = ev.x
        lastTouchY = ev.y
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(
        web: WebView,
        uaSuffix: String,
    ) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString $uaSuffix"
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkImage = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.BLACK)
        val logging =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    Log.i(TAG, "PAGE_FINISHED: url=${url.take(120)}")
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: Bitmap?,
                ) {
                    // D9: a new page is a new panel; the previous overlay is stale.
                    binding.overlay.clear(OverlayView.ClearReason.REPLACE)
                }
            }
        // Q3: only the MAIN webview gets the blocker; the chat UI must not be.
        web.webViewClient =
            if (web === binding.mainWebView) {
                AdBlockWebViewClient(enabled = { prefs.adBlockEnabled() }, delegate = logging)
            } else {
                logging
            }
    }

    private fun openUrlPrompt() {
        val input =
            EditText(this).apply {
                hint = "https://"
                setText(binding.mainWebView.url ?: START_URL)
                setSingleLine()
            }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.action_open_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.startsWith("http") }?.let {
                    binding.mainWebView.loadUrl(it)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun setMode(mode: Mode) {
        currentMode = mode
    }

    /**
     * Start/Stop. Realtime translates the visible panel immediately and again
     * whenever the viewport moves (latest-wins via [busy]); Scan translates
     * the visible panel once on Start — the chapter walk needs the panel list,
     * which the page-level runner supplies.
     */
    private fun toggleRunning() {
        running = !running
        binding.btnStartStop.text =
            getString(if (running) R.string.action_stop else R.string.action_start)
        binding.overlay.clear(OverlayView.ClearReason.DISMISS)
        Log.i(TAG, "RUN_STATE: running=$running mode=$currentMode")
        if (running) translateVisiblePanel()
    }

    fun setProgress(
        percent: Int,
        label: String,
    ) {
        binding.progress.progress = percent
        binding.progressText.text = label
    }

    private fun setWeights(mainWeight: Float) {
        mainWeights().weight = mainWeight
        chatWeights().weight = 1f - mainWeight
        binding.mainContainer.requestLayout()
        binding.chatContainer.requestLayout()
    }

    private fun mainWeights() = binding.mainContainer.layoutParams as LinearLayout.LayoutParams

    private fun chatWeights() = binding.chatContainer.layoutParams as LinearLayout.LayoutParams

    private fun weightOf(v: View): Float = (v.layoutParams as LinearLayout.LayoutParams).weight

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            binding.mainWebView.clearCache(false)
            binding.chatWebView.clearCache(false)
            Log.i(TAG, "TRIM_MEMORY: cleared webview caches level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mainWebView.clearCache(false)
        binding.chatWebView.clearCache(false)
        Log.i(TAG, "LOW_MEMORY: cleared webview caches")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val pct = weightOf(binding.mainContainer)
        Log.i(TAG, "CONFIG_CHANGED: splitWeight=$pct")
    }

    override fun onDestroy() {
        binding.mainWebView.destroy()
        binding.chatWebView.destroy()
        super.onDestroy()
    }

    enum class Mode { REALTIME, SCAN }

    var currentMode: Mode = Mode.SCAN
        private set

    companion object {
        private const val TAG = "HALUM_MAIN"
        private const val START_URL = "https://mangadex.org/"
        private const val CHAT_HOME = "https://chatgpt.com/"
        private const val MAIN_UA_SUFFIX = "Halum/0.1"
        private const val CHAT_UA_SUFFIX = "Halum/0.1"
        private const val LOG_CLIP_LABEL = "Halum log"

        /** Long-edge cap for a capture; Lens resizes to 640 regardless. */
        private const val MAX_CAPTURE_EDGE = 1280
    }
}
