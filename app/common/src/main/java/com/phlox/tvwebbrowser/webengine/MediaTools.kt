package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.util.Base64
import com.phlox.tvwebbrowser.AppContext
import org.json.JSONObject

/**
 * Smart skip and No music, done by media_tools.js in the page. The same file is the content script of the GeckoView
 * extension (the assets of this module are merged into extensions/generic/).
 */
object MediaTools {
    private const val SCRIPT_PATH = "extensions/generic/media_tools.js"
    private const val MODEL_PATH = "extensions/generic/nomusic/fastenhancer_t.wasm"
    private const val ENGINE_PATH = "extensions/generic/nomusic/engine.js"

    //events from the script
    const val EVENT_INTRO_SKIPPED = "intro_skipped"
    const val EVENT_CREDITS_SKIPPED = "credits_skipped"
    const val EVENT_NO_MUSIC_UNAVAILABLE = "no_music_unavailable"
    const val EVENT_NO_MUSIC_SLOW = "no_music_slow"
    const val EVENT_SKIP_LEARNING = "skip_learning"
    const val EVENT_SKIP_BLIND = "skip_blind"
    const val EVENT_NEXT_EPISODE = "next_episode"
    const val EVENT_RECAP_SKIPPED = "recap_skipped"
    const val EVENT_ADULT_PAGE = "adult_page" //the page calls itself adult: leave it
    val EVENTS = setOf(EVENT_INTRO_SKIPPED, EVENT_CREDITS_SKIPPED, EVENT_NO_MUSIC_UNAVAILABLE, EVENT_NO_MUSIC_SLOW,
        EVENT_SKIP_LEARNING, EVENT_SKIP_BLIND, EVENT_NEXT_EPISODE, EVENT_RECAP_SKIPPED, EVENT_ADULT_PAGE)

    //events of the app, not of the script
    const val EVENT_ADULT_BLOCKED = "adult_blocked"
    const val EVENT_HARAM_BLOCKED = "haram_blocked"
    const val EVENT_SEARCH_BLOCKED = "search_blocked"

    fun blockedEvent(url: String): String = when (ContentFilter.categoryOfSite(url)) {
        ContentFilter.Category.HARAM -> EVENT_HARAM_BLOCKED
        ContentFilter.Category.ADULT -> EVENT_ADULT_BLOCKED
        null -> EVENT_SEARCH_BLOCKED
    }

    @Volatile private var modelBase64: String? = null

    fun settings(): JSONObject {
        val config = AppContext.provideConfig()
        return JSONObject()
            .put("smartSkip", config.smartSkipEnabled)
            .put("noMusic", config.noMusicEnabled)
            .put("adultBlock", config.adultBlockEnabled)
    }

    /** The FastEnhancer model for WebView pages (they can't fetch the app assets) */
    fun modelBase64(context: Context): String {
        modelBase64?.let { return it }
        val bytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP).also { modelBase64 = it }
    }

    /** The No music engine, also the AudioWorklet module (WebView pages load it from a blob) */
    fun engineScript(context: Context): String = context.assets.open(ENGINE_PATH).bufferedReader().use { it.readText() }

    /** The script for WebView, with the host that calls the TVBro JS interface */
    fun webViewScript(context: Context): String {
        val engine = engineScript(context)
        val script = context.assets.open(SCRIPT_PATH).bufferedReader().use { it.readText() }
        return """
            (function () {
                if (!window.TVBro) return;
                $engine
                $script
                tvakhiMediaTools({
                    settings: function () {
                        try { return JSON.parse(window.TVBro.getMediaToolsSettings()); } catch (e) { return {}; }
                    },
                    loadModel: function () {
                        return new Promise(function (resolve) {
                            const binary = atob(window.TVBro.getNoMusicModel());
                            const bytes = new Uint8Array(binary.length);
                            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                            resolve(bytes.buffer);
                        });
                    },
                    workletUrls: function () {
                        try {
                            const source = window.TVBro.getNoMusicEngine();
                            return Promise.resolve([URL.createObjectURL(new Blob([source], { type: 'application/javascript' }))]);
                        } catch (e) {
                            return Promise.resolve([]);
                        }
                    },
                    notify: function (event) {
                        try { window.TVBro.onMediaToolsEvent(event); } catch (e) { }
                    }
                });
            })();
        """.trimIndent()
    }
}
