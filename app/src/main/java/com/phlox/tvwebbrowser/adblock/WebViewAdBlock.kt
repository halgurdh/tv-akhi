package com.phlox.tvwebbrowser.adblock

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.phlox.tvwebbrowser.AppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocking for the WebView engine, using uBlock Origin's default filter lists.
 * (GeckoView runs the real uBlock Origin extension instead.)
 */
object WebViewAdBlock {
    //constant: class name is obfuscated in release builds and TAG is also the prefs file name
    private const val TAG = "WebViewAdBlock"
    private const val UPDATE_INTERVAL_MS = 4L * 24 * 60 * 60 * 1000
    private const val LAST_UPDATE_PREF_KEY = "webview_adblock_lists_last_update"
    private const val MAX_INCLUDE_DEPTH = 2

    private class FilterList(val name: String, val urls: List<String>, val trusted: Boolean)

    private const val UASSETS = "https://ublockorigin.github.io/uAssets/"
    private const val UASSETS_CDN = "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@latest/"

    /** the same lists uBlock Origin enables by default */
    private val LISTS = listOf(
        FilterList("ublock-filters", listOf("${UASSETS}filters/filters.txt", "${UASSETS_CDN}filters/filters.txt"), true),
        FilterList("ublock-badware", listOf("${UASSETS}filters/badware.txt", "${UASSETS_CDN}filters/badware.txt"), true),
        FilterList("ublock-privacy", listOf("${UASSETS}filters/privacy.txt", "${UASSETS_CDN}filters/privacy.txt"), true),
        FilterList("ublock-quick-fixes", listOf("${UASSETS}filters/quick-fixes.txt", "${UASSETS_CDN}filters/quick-fixes.txt"), true),
        FilterList("ublock-unbreak", listOf("${UASSETS}filters/unbreak.txt", "${UASSETS_CDN}filters/unbreak.txt"), true),
        FilterList("easylist", listOf("${UASSETS}thirdparties/easylist.txt", "${UASSETS_CDN}thirdparties/easylist.txt"), false),
        FilterList("easyprivacy", listOf("${UASSETS}thirdparties/easyprivacy.txt", "${UASSETS_CDN}thirdparties/easyprivacy.txt"), false),
        FilterList("peter-lowe", listOf("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"), false),
        FilterList("urlhaus", listOf("https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-ag-online.txt"), false)
    )

    @Volatile
    var engine: AdBlockEngine? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var started = false
    private var contentScript: String? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch { loadAndUpdate(appContext, false) }
    }

    fun updateNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch { loadAndUpdate(appContext, true) }
    }

    private fun listsDir(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }

    private suspend fun loadAndUpdate(context: Context, force: Boolean) = mutex.withLock {
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        val dir = listsDir(context)
        val haveCache = LISTS.all { File(dir, it.name + ".txt").exists() }
        if (engine == null && haveCache) buildEngine(dir)
        val stale = System.currentTimeMillis() - prefs.getLong(LAST_UPDATE_PREF_KEY, 0) > UPDATE_INTERVAL_MS
        if (!force && haveCache && !stale) return@withLock
        var downloaded = 0
        for (list in LISTS) {
            val text = download(list.urls) ?: continue
            File(dir, list.name + ".txt").writeText(text)
            downloaded++
        }
        Log.i(TAG, "Downloaded $downloaded of ${LISTS.size} filter lists")
        if (downloaded > 0) {
            if (downloaded == LISTS.size) prefs.edit().putLong(LAST_UPDATE_PREF_KEY, System.currentTimeMillis()).apply()
            buildEngine(dir)
        }
    }

    private fun buildEngine(dir: File) {
        val started = System.currentTimeMillis()
        try {
            val builder = AdBlockEngine.Builder()
            for (list in LISTS) {
                val file = File(dir, list.name + ".txt")
                if (file.exists()) builder.addList(file.readText(), list.trusted)
            }
            val built = builder.build()
            engine = built
            Log.i(TAG, "Engine built in ${System.currentTimeMillis() - started}ms: ${built.stats}")
        } catch (e: Throwable) {
            Log.e(TAG, "Engine build failed", e)
        }
    }

    private fun download(urls: List<String>): String? {
        for (url in urls) {
            try {
                return downloadWithIncludes(url, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $url: $e")
            }
        }
        return null
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** `!#include file.txt` is resolved relative to the list url (same origin only, like uBO does) */
    private fun downloadWithIncludes(url: String, depth: Int): String {
        val text = fetch(url)
        if (depth >= MAX_INCLUDE_DEPTH || !text.contains("!#include ")) return text
        val base = URL(url)
        val sb = StringBuilder(text.length * 2)
        for (line in text.lineSequence()) {
            if (line.startsWith("!#include ")) {
                val includeUrl = URL(base, line.substring(10).trim())
                if (includeUrl.host == base.host) {
                    try {
                        sb.append(downloadWithIncludes(includeUrl.toString(), depth + 1)).append('\n')
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to include $includeUrl: $e")
                    }
                }
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun isEnabled() = AppContext.provideConfig().adBlockEnabled

    fun contentScript(context: Context): String {
        contentScript?.let { return it }
        val script = context.assets.open("adblock/content.js").bufferedReader().use { it.readText() }
        contentScript = script
        return script
    }

    // ------------------------------------------------------------------ network requests

    fun guessRequestType(request: WebResourceRequest): Int {
        if (request.isForMainFrame) return RequestType.DOCUMENT
        val headers = request.requestHeaders
        when (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"]) {
            "script" -> return RequestType.SCRIPT
            "image" -> return RequestType.IMAGE
            "style" -> return RequestType.STYLESHEET
            "iframe", "frame", "document" -> return RequestType.SUBDOCUMENT
            "font" -> return RequestType.FONT
            "audio", "video", "track" -> return RequestType.MEDIA
            "object", "embed" -> return RequestType.OBJECT
        }
        val accept = headers["Accept"] ?: headers["accept"] ?: ""
        if (accept.startsWith("text/css")) return RequestType.STYLESHEET
        if (accept.startsWith("image/")) return RequestType.IMAGE
        if (accept.startsWith("text/html")) return RequestType.SUBDOCUMENT
        val path = request.url.path?.lowercase() ?: ""
        val ext = path.substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> RequestType.SCRIPT
            "css" -> RequestType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> RequestType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RequestType.FONT
            "mp4", "webm", "m3u8", "mp3", "m4a", "m4s", "ts", "ogg", "mpd", "aac" -> RequestType.MEDIA
            else -> RequestType.UNKNOWN
        }
    }

    /**
     * Returns response to use instead of real one if request should be blocked, otherwise null.
     * [pageUrl] - url of the top level page that makes the request.
     */
    fun interceptRequest(request: WebResourceRequest, pageUrl: String?, pageFlags: Int): WebResourceResponse? {
        if (!isEnabled()) return null
        val engine = engine ?: return null
        if (pageFlags and PageFlags.DOCUMENT != 0) return null
        val url = request.url.toString()
        if (!url.startsWith("http")) return null
        val type = guessRequestType(request)
        val host = DomainUtils.hostOf(url)
        val pageHost = if (type == RequestType.DOCUMENT) host else DomainUtils.hostOf(pageUrl ?: url)
        val result = try {
            engine.network.match(Request(url, host, pageHost, type, request.method ?: "GET"))
        } catch (e: Exception) {
            Log.e(TAG, "match error", e)
            return null
        }
        if (result !is MatchResult.Blocked) return null
        if (type == RequestType.DOCUMENT) {
            if (engine.network.pageFlags(url) and PageFlags.DOCUMENT != 0) return null
            return blockedPageResponse(url, result.filter.raw)
        }
        return Redirects.response(result.filter.redirect, type)
    }

    private fun blockedPageResponse(url: String, filter: String): WebResourceResponse {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width">
            <title>Page blocked</title><style>body{font-family:sans-serif;background:#222;color:#eee;padding:40px}
            code{word-break:break-all;color:#f88}button{font-size:20px;padding:10px 20px}</style></head><body>
            <h1>Page blocked</h1><p>The ad blocker prevented this page from loading:</p><p><code>${esc(url)}</code></p>
            <p>Filter: <code>${esc(filter)}</code></p><button onclick="history.back()" autofocus>Go back</button>
            </body></html>"""
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
    }

    // ------------------------------------------------------------------ content script bridge

    class JSInterface {
        @JavascriptInterface
        fun getPageData(frameUrl: String): String {
            if (!isEnabled()) return "null"
            val engine = engine ?: return "null"
            return try {
                engine.pageData(frameUrl, engine.network.pageFlags(frameUrl))
            } catch (e: Exception) {
                Log.e(TAG, "getPageData error", e)
                "null"
            }
        }

        @JavascriptInterface
        fun getGenericCss(frameUrl: String, keysJson: String): String {
            if (!isEnabled()) return ""
            val engine = engine ?: return ""
            return try {
                val arr = JSONArray(keysJson)
                val keys = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) keys.add(arr.getString(i))
                engine.genericCss(frameUrl, keys)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
