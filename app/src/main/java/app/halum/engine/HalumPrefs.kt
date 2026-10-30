package app.halum.engine

import android.content.Context
import app.halum.core.prompt.PromptTemplate
import app.halum.util.Log

/**
 * Q2/Q3 persistence. One place for the three prompt levels, the ad-blocker
 * toggle, and per-bubble manual edits, so nothing reads SharedPreferences
 * directly.
 */
class HalumPrefs(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Q2 level 1: global prompt ----
    fun globalPrompt(): String = prefs.getString(KEY_GLOBAL_PROMPT, null) ?: PromptTemplate.specDefault()

    fun saveGlobalPrompt(text: String) {
        prefs.edit().putString(KEY_GLOBAL_PROMPT, text).apply()
        Log.i(TAG, "PROMPT_GLOBAL_SAVED: chars=${text.length}")
    }

    fun resetGlobalPrompt() {
        prefs.edit().remove(KEY_GLOBAL_PROMPT).apply()
    }

    // ---- Q2 level 3: per-bubble manual edits ----
    fun bubbleEdit(
        chapterId: String,
        panelTop: Int,
        bubbleIndex: Int,
    ): String? =
        prefs.getString(
            "bubble::$chapterId::$panelTop::$bubbleIndex",
            null,
        )

    fun saveBubbleEdit(
        chapterId: String,
        panelTop: Int,
        bubbleIndex: Int,
        text: String,
        oldLen: Int,
    ) {
        prefs.edit().putString("bubble::$chapterId::$panelTop::$bubbleIndex", text).apply()
        Log.i(TAG, "PROMPT_BUBBLE_EDIT: bubble=$bubbleIndex oldLen=$oldLen newLen=${text.length}")
    }

    // ---- Q3: ad blocker toggle (default ON) ----
    fun adBlockEnabled(): Boolean = prefs.getBoolean(KEY_ADBLOCK, true)

    fun setAdBlockEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ADBLOCK, on).apply()
    }

    // ---- D15: reading direction remembered per host ----
    fun direction(host: String): String? = prefs.getString("dir::$host", null)

    fun saveDirection(
        host: String,
        direction: String,
    ) {
        prefs.edit().putString("dir::$host", direction).apply()
    }

    private companion object {
        const val FILE = "halum"
        const val TAG = "Prefs"
        const val KEY_GLOBAL_PROMPT = "prompt::default"
        const val KEY_ADBLOCK = "adblock::enabled"
    }
}
