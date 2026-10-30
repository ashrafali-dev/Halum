package app.halum

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import app.halum.databinding.ActivityMainBinding
import app.halum.util.Log

/**
 * Two WebViews (MAIN = manga, CHAT = chatbot) with a draggable splitter.
 * Pipeline wiring arrives in Phase 3; this activity owns view lifecycle only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var dragging = false
    private var dragStartY = 0f
    private var dragStartWeight = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartY = event.rawY
                    dragStartWeight = weightOf(binding.mainContainer)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dy = event.rawY - dragStartY
                        val total = (binding.mainContainer.height + binding.chatContainer.height)
                            .coerceAtLeast(1)
                        val newWeight = (dragStartWeight + dy / total).coerceIn(0.15f, 0.85f)
                        setWeights(newWeight)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }

        setMode(Mode.SCAN)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(web: WebView, uaSuffix: String) {
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
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "PAGE_FINISHED: url=${url.take(120)}")
            }
        }
    }

    private fun openUrlPrompt() {
        val input = android.widget.EditText(this).apply {
            hint = "https://"
            setText(binding.mainWebView.url ?: START_URL)
            setSingleLine()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.action_open_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.startsWith("http") }?.let {
                    binding.mainWebView.loadUrl(it)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun setMode(mode: Mode) {
        currentMode = mode
    }

    fun setProgress(percent: Int, label: String) {
        binding.progress.progress = percent
        binding.progressText.text = label
    }

    private fun setWeights(mainWeight: Float) {
        (binding.mainContainer.layoutParams as android.widget.LinearLayout.LayoutParams).weight =
            mainWeight
        (binding.chatContainer.layoutParams as android.widget.LinearLayout.LayoutParams)
            .weight = 1f - mainWeight
        binding.mainContainer.requestLayout()
        binding.chatContainer.requestLayout()
    }

    private fun weightOf(v: View): Float =
        (v.layoutParams as android.widget.LinearLayout.LayoutParams).weight

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
