package com.phlox.tvwebbrowser.adblock

/** Request types, as bits so a request with unknown type can match several of them */
object RequestType {
    const val OTHER = 1
    const val SCRIPT = 1 shl 1
    const val IMAGE = 1 shl 2
    const val STYLESHEET = 1 shl 3
    const val OBJECT = 1 shl 4
    const val SUBDOCUMENT = 1 shl 5
    const val XHR = 1 shl 6
    const val PING = 1 shl 7
    const val MEDIA = 1 shl 8
    const val FONT = 1 shl 9
    const val WEBSOCKET = 1 shl 10
    const val DOCUMENT = 1 shl 11
    /** a new window opened by the page; only `$popup` filters (and plain `||host^` ones) apply to it */
    const val POPUP = 1 shl 12

    const val ALL_BUT_DOCUMENT = (1 shl 11) - 1
    const val ALL = ALL_BUT_DOCUMENT or DOCUMENT or POPUP
    /** WebView doesn't tell the resource type, this is used when it can't be guessed */
    const val UNKNOWN = SCRIPT or XHR or OTHER

    fun fromOption(name: String): Int = when (name) {
        "script" -> SCRIPT
        "image" -> IMAGE
        "stylesheet", "css" -> STYLESHEET
        "object", "object-subrequest" -> OBJECT
        "subdocument", "frame" -> SUBDOCUMENT
        "xmlhttprequest", "xhr" -> XHR
        "ping", "beacon" -> PING
        "media" -> MEDIA
        "font" -> FONT
        "websocket" -> WEBSOCKET
        "other" -> OTHER
        "document", "doc" -> DOCUMENT
        "popup" -> POPUP
        "all" -> ALL
        else -> 0
    }
}

/** Page-level exception flags (from @@ filters with $document, $elemhide, $generichide, $specifichide) */
object PageFlags {
    const val DOCUMENT = 1
    const val ELEMHIDE = 2
    const val GENERICHIDE = 4
    const val SPECIFICHIDE = 8
}

class Request(
    val url: String,
    val host: String,
    val pageHost: String,
    val typeBits: Int,
    val method: String = "GET"
) {
    val urlLower: String = url.lowercase()
    val thirdParty: Boolean = DomainUtils.isThirdParty(host, pageHost)
    /** host, its parents and entity forms - for fast `domain=` checks */
    val hostKeys: Set<String> by lazy(LazyThreadSafetyMode.NONE) { DomainUtils.domainKeys(host) }
    val pageHostKeys: Set<String> by lazy(LazyThreadSafetyMode.NONE) { DomainUtils.domainKeys(pageHost) }
}

class NetworkFilter(
    val raw: String,
    val isException: Boolean,
    private val regex: Regex?,
    private val segments: Array<String>,
    private val hostAnchor: Boolean,
    private val leftAnchor: Boolean,
    private val rightAnchor: Boolean,
    val typeMask: Int,
    private val party: Int,
    private val domains: DomainList?,
    private val toDomains: DomainList?,
    private val denyAllow: DomainList?,
    private val methods: Set<String>?,
    val important: Boolean,
    private val matchCase: Boolean,
    val redirect: String?,
    val pageFlags: Int,
    /** hostname for `||host^` filters, used for hash lookup */
    val pureHost: String?,
    /** index token, 0 if none */
    val token: Int
) {
    fun matches(req: Request, checkType: Boolean = true): Boolean {
        if (checkType && typeMask and req.typeBits == 0) return false
        if (party == PARTY_FIRST && req.thirdParty) return false
        if (party == PARTY_THIRD && !req.thirdParty) return false
        if (domains != null && !domains.matches(req.pageHostKeys)) return false
        if (toDomains != null && !toDomains.matches(req.hostKeys)) return false
        if (denyAllow != null && denyAllow.include.any { it in req.hostKeys }) return false
        if (methods != null && !methods.contains(req.method.lowercase())) return false
        return matchesUrl(req)
    }

    private fun matchesUrl(req: Request): Boolean {
        val u = if (matchCase) req.url else req.urlLower
        if (regex != null) return regex.containsMatchIn(u)
        if (pureHost != null) return true //host already matched by index lookup
        if (hostAnchor) {
            val hostStart = u.indexOf("://").let { if (it < 0) return false else it + 3 }
            var hostEnd = hostStart
            while (hostEnd < u.length && u[hostEnd] != '/' && u[hostEnd] != '?' && u[hostEnd] != '#') hostEnd++
            var start = hostStart
            while (start < hostEnd) {
                if (matchFrom(u, start)) return true
                val dot = u.indexOf('.', start)
                if (dot < 0 || dot >= hostEnd) break
                start = dot + 1
            }
            return false
        }
        if (leftAnchor) return matchFrom(u, 0)
        return matchAnywhere(u)
    }

    private fun matchFrom(u: String, start: Int): Boolean {
        var pos = segMatchAt(u, start, segments[0])
        if (pos < 0) return false
        if (segments.size == 1) return !rightAnchor || pos == u.length
        for (i in 1 until segments.size) {
            val seg = segments[i]
            if (i == segments.size - 1 && rightAnchor) return segMatchesTail(u, pos, seg)
            if (seg.isEmpty()) continue
            val end = segFind(u, pos, seg)
            if (end < 0) return false
            pos = end
        }
        return true
    }

    private fun matchAnywhere(u: String): Boolean {
        val first = segments[0]
        if (segments.size == 1) {
            if (rightAnchor) return segMatchesTail(u, 0, first)
            return first.isEmpty() || segFind(u, 0, first) >= 0
        }
        val end = if (first.isEmpty()) 0 else segFind(u, 0, first)
        if (end < 0) return false
        var pos = end
        for (i in 1 until segments.size) {
            val seg = segments[i]
            if (i == segments.size - 1 && rightAnchor) return segMatchesTail(u, pos, seg)
            if (seg.isEmpty()) continue
            val e = segFind(u, pos, seg)
            if (e < 0) return false
            pos = e
        }
        return true
    }

    private fun segMatchesTail(u: String, from: Int, seg: String): Boolean {
        for (p in u.length downTo from) {
            if (segMatchAt(u, p, seg) == u.length) return true
            if (u.length - p > seg.length) break
        }
        return false
    }

    companion object {
        const val PARTY_ANY = 0
        const val PARTY_FIRST = 1
        const val PARTY_THIRD = 2

        private fun isSeparator(c: Char): Boolean =
            !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** match segment (may contain ^) at position, returns end position or -1 */
        fun segMatchAt(u: String, pos: Int, seg: String): Int {
            var p = pos
            for (k in seg.indices) {
                val c = seg[k]
                if (c == '^') {
                    if (p == u.length) {
                        if (k == seg.length - 1) return p
                        return -1
                    }
                    if (!isSeparator(u[p])) return -1
                    p++
                } else {
                    if (p >= u.length || u[p] != c) return -1
                    p++
                }
            }
            return p
        }

        /** find leftmost occurrence of segment from position, returns end position or -1 */
        fun segFind(u: String, from: Int, seg: String): Int {
            val first = seg[0]
            var p = from
            while (p <= u.length) {
                if (first != '^') {
                    p = u.indexOf(first, p)
                    if (p < 0) return -1
                }
                val end = segMatchAt(u, p, seg)
                if (end >= 0) return end
                p++
            }
            return -1
        }
    }
}
