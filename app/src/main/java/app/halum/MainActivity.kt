package app.halum

import android.annotation.SuppressLint
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
import app.halum.engine.AdBlockWebViewClient
import app.halum.engine.AndroidPanelPipeline
import app.halum.engine.BubbleDetector
import app.halum.engine.HalumPrefs
import app.halum.engine.LensPort
import app.halum.engine.WebViewChatPage
import app.halum.model.Models
import app.halum.util.Log
import app.halum.databinding.ActivityMainBinding
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

        // D9: user scroll dismisses the overlay; a pipeline transition must not.
        binding.mainWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY != lastOverlayScrollY) {
                lastOverlayScrollY = scrollY
                binding.overlay.clear(app.halum.overlay.OverlayView.ClearReason.SCROLL)
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

    /** Captures the visible MAIN panel with PixelCopy and runs the pipeline. */
    private fun translateVisiblePanel() {
        setProgress(0, getString(R.string.progress_working))
        binding.mainWebView.post {
            captureView(binding.mainWebView) { bitmap ->
                if (bitmap == null) {
                    setProgress(0, getString(R.string.error_capture_failed))
                    return@captureView
                }
                val p = pipeline ?: buildPipeline().also { pipeline = it }
                worker.execute {
                    val outcome = runCatching { p.process(bitmap) }.getOrNull()
                    bitmap.recycle()
                    runOnUiThread { deliver(outcome) }
                }
            }
        }
    }

    private fun deliver(outcome: AndroidPanelPipeline.PanelOutcome?) {
        if (outcome == null) {
            setProgress(0, getString(R.string.error_lens_unavailable))
            return
        }
        val placements =
            outcome.placements.map {
                app.halum.overlay.OverlayView.Placement(it.box, it.text, it.isSmall)
            }
        binding.overlay.show(placements, binding.mainWebView.scrollY)
        setProgress(100, getString(R.string.progress_panel, outcome.placements.size, outcome.bubbles))
        Log.i(TAG, "OVERLAY_PLACED: count=${placements.size}")
    }

    /** D20/PixelCopy-free: draw the WebView into a bitmap off the UI thread. */
    private fun captureView(
        view: View,
        onDone: (Bitmap?) -> Unit,
    ) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            onDone(null)
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        view.draw(canvas)
        onDone(bmp)
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
                    binding.overlay.clear(app.halum.overlay.OverlayView.ClearReason.REPLACE)
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
    }
}
