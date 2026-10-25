package com.phlox.tvwebbrowser.webengine

import android.net.Uri
import android.util.Log
import com.phlox.tvwebbrowser.AppContext
import java.nio.ByteBuffer

/**
 * Blocks the sites of a category, see tools/make_filter_lists.py for the lists (assets/filters/):
 *  - adult: StevenBlack's porn list, plus SafeSearch on the search engines. Pages that call themselves adult
 *    (the RTA label) are closed by media_tools.js;
 *  - haram: drugs, gambling and vaping (The Block List Project), plus alcohol and gambling words in the domain name.
 * Searches for either are blocked too, see [SearchFilter].
 */
object ContentFilter {
    private const val TAG = "ContentFilter"

    enum class Category(val listPath: String) {
        ADULT("filters/adult.bin"),
        HARAM("filters/haram.bin");

        val searchKind: SearchFilter.Kind
            get() = when (this) {
                ADULT -> SearchFilter.Kind.ADULT
                HARAM -> SearchFilter.Kind.HARAM
            }

        val isEnabled: Boolean
            get() = when (this) {
                ADULT -> AppContext.provideConfig().adultBlockEnabled
                HARAM -> AppContext.provideConfig().haramBlockEnabled
            }

        internal val hashes: LongArray by lazy {
            try {
                val bytes = AppContext.get().assets.open(listPath).use { it.readBytes() }
                LongArray(bytes.size / 8).also { ByteBuffer.wrap(bytes).asLongBuffer().get(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Can't load $listPath", e)
                LongArray(0)
            }
        }
    }

    //whole top level domains and words in a domain name (alcohol has no list)
    private val HARAM_TLDS = setOf("casino", "bet", "poker", "bingo", "lotto", "beer", "vodka", "wine", "cannabis")
    private val HARAM_WORDS = Regex("casino|poker|vodka|whisky|whiskey|liquor|brewery|bourbon|tequila|cannabis|marijuana")
    private val HARAM_LABELS = setOf("betting", "beer", "wine", "wines", "weed", "vape", "cbd", "slots")

    //search engines and their query parameter
    private val SEARCH_ENGINES = listOf(
        "google." to "q", "bing.com" to "q", "duckduckgo.com" to "q", "yahoo.com" to "p", "yandex." to "text",
        "youtube.com" to "search_query", "ecosia.org" to "q", "search.brave.com" to "q", "startpage.com" to "query",
        "qwant.com" to "q", "baidu.com" to "wd", "ask.com" to "q", "aol.com" to "q"
    )

    /** Loads the lists in the background, so the first page doesn't wait */
    fun preload() {
        Thread { Category.entries.forEach { if (it.isEnabled) it.hashes.size } }.start()
    }

    /** The url to load (with SafeSearch when the adult block is on). Null: blocked, see [categoryOfUrl]. */
    fun check(url: String): String? {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        if (blockedCategory(uri.host) != null || searchCategory(uri) != null) return null
        if (!Category.ADULT.isEnabled) return url
        return safeSearch(uri)?.toString() ?: url
    }

    /** The category of the site or of the search. Null: allowed. */
    fun categoryOfUrl(url: String): Category? =
        try { Uri.parse(url).let { blockedCategory(it.host) ?: searchCategory(it) } } catch (e: Exception) { null }

    /** The category of the site only (null for an allowed site with a blocked search) */
    fun categoryOfSite(url: String): Category? =
        try { blockedCategory(Uri.parse(url).host) } catch (e: Exception) { null }

    private fun searchCategory(uri: Uri): Category? {
        val host = uri.host?.lowercase() ?: return null
        val param = SEARCH_ENGINES.firstOrNull { (engine, _) ->
            if (engine.endsWith('.')) host.startsWith(engine) || host.contains(".$engine")
            else host == engine || host.endsWith(".$engine")
        }?.second ?: return null
        val query = try { uri.getQueryParameter(param) } catch (e: Exception) { null } ?: return null
        val enabled = Category.entries.filter { it.isEnabled }
        val kind = SearchFilter.kindOf(query, enabled.map { it.searchKind }.toSet()) ?: return null
        return enabled.first { it.searchKind == kind }
    }

    /** The category of the host or of one of its parent domains. Null: allowed. */
    fun blockedCategory(host: String?): Category? {
        var domain = host?.lowercase()?.trimEnd('.') ?: return null
        if (domain.startsWith("www.")) domain = domain.substring(4)
        val enabled = Category.entries.filter { it.isEnabled }
        if (enabled.isEmpty()) return null
        if (Category.HARAM in enabled && isHaramName(domain)) return Category.HARAM
        while (domain.contains('.')) {
            val hash = fnv1a64(domain)
            enabled.firstOrNull { it.hashes.binarySearch(hash) >= 0 }?.let { return it }
            domain = domain.substringAfter('.')
        }
        return null
    }

    private fun isHaramName(domain: String): Boolean {
        val labels = domain.split('.')
        if (labels.last() in HARAM_TLDS) return true
        val name = labels.dropLast(1)
        return name.any { HARAM_WORDS.containsMatchIn(it) } || name.flatMap { it.split('-') }.any { it in HARAM_LABELS }
    }

    /** The search with SafeSearch strict. Null: not a search or already strict. */
    private fun safeSearch(uri: Uri): Uri? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: ""
        val (name, value) = when {
            (host.startsWith("google.") || host.startsWith("www.google.")) && path == "/search" -> "safe" to "active"
            host.endsWith("bing.com") && path == "/search" -> "adlt" to "strict"
            host.endsWith("duckduckgo.com") && uri.getQueryParameter("q") != null -> "kp" to "1"
            host == "search.yahoo.com" && path.startsWith("/search") -> "vm" to "r"
            else -> return null
        }
        if (uri.getQueryParameter(name) == value) return null
        val builder = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            if (key == name) continue
            for (v in uri.getQueryParameters(key)) builder.appendQueryParameter(key, v)
        }
        return builder.appendQueryParameter(name, value).build()
    }

    private fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL //0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
