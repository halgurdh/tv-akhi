package com.phlox.tvwebbrowser.adblock

/** Cosmetic or scriptlet filter line: `domains##body`, `domains#@#body` */
class CosmeticLine(
    val domains: DomainList?,
    val isException: Boolean,
    val body: String
)

object FilterParser {
    private val BAD_TOKENS = hashSetOf("com", "http", "https", "icon", "images", "img", "js", "net", "news", "www")

    /** Options whose filters we can't honour; such filters are dropped instead of being applied wrongly */
    private val UNSUPPORTED_OPTIONS = hashSetOf(
        "popunder", "removeparam", "queryprune", "csp", "header", "permissions", "replace",
        "urlskip", "uritransform", "urltransform", "rewrite", "cname", "ipaddress", "inline-script",
        "inline-font", "top", "redirect-rule", "empty", "mp4", "webrtc", "genericblock", "elemhide-exception"
    )

    /** Preprocessor tokens (`!#if`) that are true for us: Chromium-based WebView on a TV */
    private val ENV_TRUE = hashSetOf("ext_ublock", "env_chromium", "env_mobile", "cap_user_stylesheet")

    fun evalPreprocessorCondition(expr: String): Boolean {
        //supports !, &&, || and parentheses
        val tokens = Regex("""\(|\)|!|&&|\|\||[A-Za-z0-9_]+""").findAll(expr).map { it.value }.toList()
        var pos = 0
        fun parseOr(): Boolean {
            fun parseUnary(): Boolean {
                if (pos >= tokens.size) return false
                val t = tokens[pos++]
                return when (t) {
                    "!" -> !parseUnary()
                    "(" -> { val v = parseOr(); if (pos < tokens.size && tokens[pos] == ")") pos++; v }
                    else -> t in ENV_TRUE
                }
            }
            fun parseAnd(): Boolean {
                var v = parseUnary()
                while (pos < tokens.size && tokens[pos] == "&&") { pos++; val r = parseUnary(); v = v && r }
                return v
            }
            var v = parseAnd()
            while (pos < tokens.size && tokens[pos] == "||") { pos++; val r = parseAnd(); v = v || r }
            return v
        }
        return parseOr()
    }

    /** Applies `!#if/!#else/!#endif` blocks, returns the active lines */
    fun preprocess(lines: Sequence<String>): Sequence<String> = sequence {
        val stack = ArrayList<Boolean>() //active state per nesting level
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("!#if ")) {
                val parentActive = stack.all { it }
                stack.add(parentActive && evalPreprocessorCondition(line.substring(5)))
                continue
            }
            if (line.startsWith("!#else")) {
                if (stack.isNotEmpty()) {
                    val parentActive = stack.subList(0, stack.size - 1).all { it }
                    stack[stack.size - 1] = parentActive && !stack[stack.size - 1]
                }
                continue
            }
            if (line.startsWith("!#endif")) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            if (stack.all { it }) yield(line)
        }
    }

    /** Index of cosmetic separator (`##`, `#@#`, `#?#`, `#@?#`) or -1 if line is not a cosmetic filter */
    private fun cosmeticSeparator(line: String): Int {
        var i = line.indexOf('#')
        while (i >= 0) {
            if (line.startsWith("##", i) || line.startsWith("#@#", i) || line.startsWith("#?#", i) ||
                line.startsWith("#@?#", i) || line.startsWith("#$#", i) || line.startsWith("#@$#", i) ||
                line.startsWith("#%#", i) || line.startsWith("#@%#", i)) {
                val domainPart = line.substring(0, i)
                if (domainPart.all { it.isLetterOrDigit() || it in ".-_*~,:[]" }) return i
                return -1
            }
            i = line.indexOf('#', i + 1)
        }
        return -1
    }

    /** Returns CosmeticLine, NetworkFilter or null for comments/unsupported filters */
    fun parseLine(line: String): Any? {
        if (line.isEmpty() || line.startsWith('!') || line.startsWith('[')) return null
        val sep = cosmeticSeparator(line)
        if (sep >= 0) return parseCosmetic(line, sep)
        return parseNetwork(line)
    }

    private fun parseCosmetic(line: String, sep: Int): CosmeticLine? {
        val rest = line.substring(sep)
        val isException = rest.startsWith("#@")
        val marker = when {
            rest.startsWith("#@?#") -> "#@?#"
            rest.startsWith("#@#") -> "#@#"
            rest.startsWith("#?#") -> "#?#"
            rest.startsWith("##") -> "##"
            else -> return null //#$#, #%# - AdGuard specific
        }
        val body = rest.substring(marker.length).trim()
        if (body.isEmpty() || body.startsWith('^')) return null //html filtering is not available in WebView
        val domainPart = line.substring(0, sep)
        val domains = if (domainPart.isEmpty()) null else (DomainList.parse(domainPart, ',') ?: return null)
        return CosmeticLine(domains, isException, body)
    }

    private fun optionsStart(line: String): Int {
        val idx = line.lastIndexOf('$')
        if (idx < 0) return -1
        if (line.startsWith('/') || line.startsWith("@@/")) {
            //`$` inside of regex
            if (idx < line.lastIndexOf('/')) return -1
        }
        return idx
    }

    fun parseNetwork(line: String): NetworkFilter? {
        var s = line
        val isException = s.startsWith("@@")
        if (isException) s = s.substring(2)
        val optIdx = optionsStart(s)
        var pattern = if (optIdx >= 0) s.substring(0, optIdx) else s
        val options = if (optIdx >= 0) s.substring(optIdx + 1) else ""

        var typeMask = 0
        var negTypeMask = 0
        var party = NetworkFilter.PARTY_ANY
        var domains: DomainList? = null
        var toDomains: DomainList? = null
        var denyAllow: DomainList? = null
        var methods: Set<String>? = null
        var important = false
        var matchCase = false
        var redirect: String? = null
        var pageFlags = 0

        if (options.isNotEmpty()) {
            for (rawOpt in splitOptions(options)) {
                val opt = rawOpt.trim()
                if (opt.isEmpty()) continue
                val eq = opt.indexOf('=')
                val name = (if (eq >= 0) opt.substring(0, eq) else opt).lowercase()
                val value = if (eq >= 0) opt.substring(eq + 1) else ""
                val negated = name.startsWith('~')
                val n = if (negated) name.substring(1) else name
                if (n in UNSUPPORTED_OPTIONS) return null
                val type = RequestType.fromOption(n)
                when {
                    n == "document" || n == "doc" -> {
                        if (isException) pageFlags = pageFlags or PageFlags.DOCUMENT
                        else if (negated) negTypeMask = negTypeMask or RequestType.DOCUMENT
                        else typeMask = typeMask or RequestType.DOCUMENT
                    }
                    type != 0 -> if (negated) negTypeMask = negTypeMask or type else typeMask = typeMask or type
                    n == "third-party" || n == "3p" || n == "strict3p" ->
                        party = if (negated) NetworkFilter.PARTY_FIRST else NetworkFilter.PARTY_THIRD
                    n == "first-party" || n == "1p" || n == "strict1p" ->
                        party = if (negated) NetworkFilter.PARTY_THIRD else NetworkFilter.PARTY_FIRST
                    n == "domain" || n == "from" -> domains = DomainList.parse(value, '|') ?: return null
                    n == "to" -> toDomains = DomainList.parse(value, '|') ?: return null
                    n == "denyallow" -> denyAllow = DomainList.parse(value, '|') ?: return null
                    n == "method" -> methods = value.lowercase().split('|').filter { !it.startsWith('~') }.toSet()
                    n == "important" -> important = true
                    n == "match-case" -> matchCase = true
                    n == "redirect" -> redirect = value.substringBefore(':')
                    n == "elemhide" || n == "ehide" -> pageFlags = pageFlags or PageFlags.ELEMHIDE
                    n == "generichide" || n == "ghide" -> pageFlags = pageFlags or PageFlags.GENERICHIDE
                    n == "specifichide" || n == "shide" -> pageFlags = pageFlags or PageFlags.SPECIFICHIDE
                    n == "badfilter" -> {} //handled by engine builder
                    n == "reason" || n == "_" || n.startsWith("_") -> {}
                    else -> return null
                }
            }
        }
        if (pageFlags != 0 && !isException) return null

        var finalTypeMask = when {
            typeMask != 0 -> typeMask
            negTypeMask != 0 -> RequestType.ALL_BUT_DOCUMENT
            pageFlags != 0 -> 0
            else -> RequestType.ALL_BUT_DOCUMENT
        }
        finalTypeMask = finalTypeMask and negTypeMask.inv()
        if (pageFlags != 0 && typeMask == 0) finalTypeMask = 0

        if (pattern == "*") pattern = ""
        //regex filter
        if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) {
            val regex = try {
                if (matchCase) Regex(pattern.substring(1, pattern.length - 1))
                else Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return null
            }
            return NetworkFilter(line, isException, regex, arrayOf(""), false, false, false, finalTypeMask,
                party, domains, toDomains, denyAllow, methods, important, matchCase, redirect, pageFlags, null, 0)
        }

        var hostAnchor = false
        var leftAnchor = false
        var rightAnchor = false
        if (pattern.startsWith("||")) { hostAnchor = true; pattern = pattern.substring(2) }
        else if (pattern.startsWith('|')) { leftAnchor = true; pattern = pattern.substring(1) }
        if (pattern.endsWith('|')) { rightAnchor = true; pattern = pattern.substring(0, pattern.length - 1) }
        //leading/trailing wildcards don't change anything for unanchored match
        while (!hostAnchor && !leftAnchor && pattern.startsWith('*')) pattern = pattern.substring(1)
        while (!rightAnchor && pattern.endsWith('*')) pattern = pattern.substring(0, pattern.length - 1)
        if (!matchCase) pattern = pattern.lowercase()

        var pureHost: String? = null
        if (hostAnchor && !rightAnchor && pattern.endsWith('^')) {
            val h = pattern.substring(0, pattern.length - 1)
            if (h.isNotEmpty() && h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) pureHost = h
        }
        if (hostAnchor && !rightAnchor && pureHost == null && pattern.isNotEmpty() &&
            pattern.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } && pattern.contains('.')) {
            //`||example.com` without `^` - treat as hostname too (common in lists)
            pureHost = pattern
        }

        val segments = pattern.split('*').toTypedArray()
        val token = if (pureHost != null) 0 else bestToken(pattern, hostAnchor || leftAnchor, rightAnchor)
        return NetworkFilter(line, isException, null, segments, hostAnchor, leftAnchor, rightAnchor, finalTypeMask,
            party, domains, toDomains, denyAllow, methods, important, matchCase, redirect, pageFlags, pureHost, token)
    }

    private fun splitOptions(options: String): List<String> {
        //commas inside of regex-like values (e.g. domain=/a,b/) are rare, plain split is fine
        return options.split(',')
    }

    fun isTokenChar(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || c == '%' || (c in 'A'..'Z')

    /** Picks best token from pattern: complete alnum run not adjacent to wildcard */
    private fun bestToken(pattern: String, leftAnchored: Boolean, rightAnchored: Boolean): Int {
        var best: String? = null
        var i = 0
        while (i < pattern.length) {
            if (!isTokenChar(pattern[i])) { i++; continue }
            val start = i
            while (i < pattern.length && isTokenChar(pattern[i])) i++
            val end = i
            val leftOk = if (start == 0) leftAnchored else pattern[start - 1] != '*'
            val rightOk = if (end == pattern.length) rightAnchored else pattern[end] != '*'
            if (!leftOk || !rightOk) continue
            val tok = pattern.substring(start, end).lowercase()
            if (tok.length < 2 || tok in BAD_TOKENS) continue
            if (best == null || tok.length > best.length) best = tok
        }
        return best?.hashCode() ?: 0
    }

    /** Hashes of all tokens in url, the same way as [bestToken] does */
    fun urlTokens(urlLower: String): IntArray {
        val result = IntArray(64)
        var count = 0
        var i = 0
        while (i < urlLower.length && count < result.size) {
            if (!isTokenChar(urlLower[i])) { i++; continue }
            var h = 0
            while (i < urlLower.length && isTokenChar(urlLower[i])) { h = 31 * h + urlLower[i].code; i++ }
            result[count++] = h
        }
        return result.copyOf(count)
    }

    /** `badfilter` support: the text of the filter this badfilter disables */
    fun badfilterTarget(line: String): String? {
        val idx = line.lastIndexOf('$')
        if (idx < 0) return null
        val opts = line.substring(idx + 1).split(',')
        if (opts.none { it.trim() == "badfilter" }) return null
        val rest = opts.filter { it.trim() != "badfilter" }
        return if (rest.isEmpty()) line.substring(0, idx) else line.substring(0, idx) + "$" + rest.joinToString(",")
    }
}
