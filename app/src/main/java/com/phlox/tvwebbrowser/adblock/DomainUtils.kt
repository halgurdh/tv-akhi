package com.phlox.tvwebbrowser.adblock

/**
 * Hostname helpers for the filter engine. Uses a small built-in list of multi-label public suffixes
 * instead of the full Public Suffix List - good enough for first/third-party checks and `example.*` entities.
 */
object DomainUtils {
    private val MULTI_LABEL_SUFFIXES = hashSetOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "ltd.uk", "plc.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "com.br", "net.br", "org.br", "gov.br",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "com.tw", "org.tw", "net.tw", "idv.tw",
        "com.hk", "org.hk", "net.hk",
        "com.sg", "org.sg", "net.sg",
        "com.my", "net.my", "org.my",
        "co.id", "or.id", "web.id",
        "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in",
        "co.nz", "org.nz", "net.nz",
        "co.za", "org.za", "net.za",
        "co.il", "org.il", "net.il",
        "co.th", "in.th", "or.th",
        "com.tr", "net.tr", "org.tr", "gen.tr",
        "com.mx", "org.mx", "net.mx", "gob.mx",
        "com.ar", "net.ar", "org.ar", "gob.ar",
        "com.co", "net.co", "org.co",
        "com.pe", "net.pe", "org.pe",
        "com.ve", "net.ve",
        "com.ua", "net.ua", "org.ua", "in.ua",
        "com.ru", "net.ru", "org.ru", "msk.ru", "spb.ru",
        "com.pl", "net.pl", "org.pl",
        "com.eg", "net.eg", "org.eg",
        "com.sa", "net.sa", "org.sa",
        "com.ae", "net.ae", "org.ae",
        "com.iq", "net.iq", "org.iq",
        "com.kw", "com.qa", "com.bh", "com.om", "com.jo", "com.lb", "com.ly", "com.dz", "co.ma",
        "com.pk", "net.pk", "org.pk",
        "com.bd", "net.bd",
        "com.vn", "net.vn",
        "com.ph", "net.ph",
        "com.ng", "com.gh", "co.ke",
        "github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "netlify.app", "vercel.app",
        "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "appspot.com", "cloudfront.net",
        "azurewebsites.net", "wordpress.com", "tumblr.com"
    )

    /** Extracts lowercase hostname from an absolute URL, or "" if there is none. */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        var start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') { end = i; break }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        if (start < end && url[start] == '[') {
            val close = url.indexOf(']', start)
            if (close in start until end) return url.substring(start, close + 1).lowercase()
        }
        val colon = url.indexOf(':', start)
        if (colon in start until end) end = colon
        return url.substring(start, end).lowercase()
    }

    private fun isIp(host: String): Boolean {
        if (host.startsWith('[')) return true
        if (host.isEmpty()) return false
        for (c in host) if (!(c.isDigit() || c == '.')) return false
        return true
    }

    /** Public suffix of the host, e.g. "co.uk" for "www.bbc.co.uk". */
    fun publicSuffix(host: String): String {
        if (isIp(host)) return host
        val last = host.lastIndexOf('.')
        if (last < 0) return host
        val prev = host.lastIndexOf('.', last - 1)
        val twoLabels = if (prev < 0) host else host.substring(prev + 1)
        if (twoLabels in MULTI_LABEL_SUFFIXES) return twoLabels
        return host.substring(last + 1)
    }

    /** eTLD+1, e.g. "bbc.co.uk" for "www.bbc.co.uk". */
    fun registrableDomain(host: String): String {
        if (isIp(host)) return host
        val suffix = publicSuffix(host)
        if (suffix.length >= host.length) return host
        val beforeSuffix = host.length - suffix.length - 1
        val dot = host.lastIndexOf('.', beforeSuffix - 1)
        return if (dot < 0) host else host.substring(dot + 1)
    }

    fun isThirdParty(requestHost: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || requestHost.isEmpty()) return false
        return registrableDomain(requestHost) != registrableDomain(pageHost)
    }

    /** The host itself and all parent domains: a.b.com -> [a.b.com, b.com, com] */
    fun hostAndParents(host: String): List<String> {
        val result = ArrayList<String>(4)
        if (host.isEmpty()) return result
        result.add(host)
        if (isIp(host)) return result
        var pos = host.indexOf('.')
        while (pos >= 0) {
            result.add(host.substring(pos + 1))
            pos = host.indexOf('.', pos + 1)
        }
        return result
    }

    /** Entity forms of the host used by `example.*` syntax: www.google.co.uk -> [www.google.*, google.*] */
    fun entities(host: String): List<String> {
        if (isIp(host)) return emptyList()
        val suffix = publicSuffix(host)
        if (suffix.length >= host.length) return emptyList()
        val base = host.substring(0, host.length - suffix.length - 1)
        val result = ArrayList<String>(2)
        result.add("$base.*")
        var pos = base.indexOf('.')
        while (pos >= 0) {
            result.add(base.substring(pos + 1) + ".*")
            pos = base.indexOf('.', pos + 1)
        }
        return result
    }

    fun domainKeys(host: String): Set<String> {
        val keys = HashSet<String>(8)
        keys.addAll(hostAndParents(host))
        keys.addAll(entities(host))
        return keys
    }

    /** Does [host] match a filter domain [d] (plain domain covers subdomains; `name.*` is an entity) */
    fun matches(host: String, d: String): Boolean {
        if (d.endsWith(".*")) {
            return entities(host).contains(d)
        }
        return host == d || (host.length > d.length && host.endsWith(d) && host[host.length - d.length - 1] == '.')
    }
}

/** Parsed `domain=a.com|~b.a.com|c.*` style list. */
class DomainList(val include: Array<String>, val exclude: Array<String>) {
    /** [keys] - result of [DomainUtils.domainKeys] */
    fun matches(keys: Set<String>): Boolean {
        if (include.isNotEmpty() && include.none { it in keys }) return false
        return exclude.none { it in keys }
    }

    fun matches(host: String): Boolean {
        if (include.isNotEmpty() && include.none { DomainUtils.matches(host, it) }) return false
        return exclude.none { DomainUtils.matches(host, it) }
    }

    companion object {
        /** returns null if list contains unsupported (regex) entries */
        fun parse(value: String, separator: Char): DomainList? {
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            for (part in value.split(separator)) {
                var d = part.trim().lowercase()
                if (d.isEmpty()) continue
                val negated = d.startsWith('~')
                if (negated) d = d.substring(1)
                if (d.startsWith('/')) return null
                if (negated) exc.add(d) else inc.add(d)
            }
            return DomainList(inc.toTypedArray(), exc.toTypedArray())
        }
    }
}
