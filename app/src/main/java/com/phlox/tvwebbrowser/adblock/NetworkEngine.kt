package com.phlox.tvwebbrowser.adblock

/** Filters indexed by hostname (for `||host^`), by token and the rest (no usable token) */
class FilterIndex {
    private val byHost = HashMap<String, ArrayList<NetworkFilter>>()
    private val byToken = HashMap<Int, ArrayList<NetworkFilter>>()
    private val untokenized = ArrayList<NetworkFilter>()
    /** untokenized filters that apply only to documents - many regex filters from badware list */
    private val untokenizedDocOnly = ArrayList<NetworkFilter>()
    var size = 0
        private set

    fun add(f: NetworkFilter) {
        size++
        when {
            f.pureHost != null -> byHost.getOrPut(f.pureHost) { ArrayList(1) }.add(f)
            f.token != 0 -> byToken.getOrPut(f.token) { ArrayList(1) }.add(f)
            f.typeMask == RequestType.DOCUMENT -> untokenizedDocOnly.add(f)
            else -> untokenized.add(f)
        }
    }

    private inline fun forEachCandidate(req: Request, hostParents: List<String>, tokens: IntArray, action: (NetworkFilter) -> Boolean) {
        for (h in hostParents) byHost[h]?.let { list -> for (f in list) if (action(f)) return }
        for (t in tokens) byToken[t]?.let { list -> for (f in list) if (action(f)) return }
        for (f in untokenized) if (action(f)) return
        if (req.typeBits and RequestType.DOCUMENT != 0) for (f in untokenizedDocOnly) if (action(f)) return
    }

    /** First matching filter; an `important` one is preferred if [preferImportant] */
    fun find(req: Request, hostParents: List<String>, tokens: IntArray, preferImportant: Boolean): NetworkFilter? {
        var found: NetworkFilter? = null
        forEachCandidate(req, hostParents, tokens) { f ->
            if ((found == null || (preferImportant && f.important)) && f.matches(req)) {
                found = f
                !preferImportant || f.important
            } else false
        }
        return found
    }

    fun findAll(req: Request, hostParents: List<String>, tokens: IntArray, checkType: Boolean): List<NetworkFilter> {
        val result = ArrayList<NetworkFilter>(0)
        forEachCandidate(req, hostParents, tokens) { f ->
            if (f.matches(req, checkType)) result.add(f)
            false
        }
        return result
    }


    fun trimToSize() {
        byHost.values.forEach { it.trimToSize() }
        byToken.values.forEach { it.trimToSize() }
        untokenized.trimToSize()
        untokenizedDocOnly.trimToSize()
    }
}

sealed class MatchResult {
    object None : MatchResult()
    class Blocked(val filter: NetworkFilter) : MatchResult()
    class Allowed(val filter: NetworkFilter) : MatchResult()
}

class NetworkEngine {
    private val blocking = FilterIndex()
    private val exceptions = FilterIndex()
    /** exception filters with page-level flags ($document, $generichide...) */
    private val pageExceptions = FilterIndex()
    /** `||host^` filters without options - the majority of all filters, kept as plain strings */
    private val plainHosts = HashSet<String>()

    val size: Int get() = blocking.size + exceptions.size + pageExceptions.size + plainHosts.size

    fun addPlainHost(host: String) {
        plainHosts.add(host)
    }

    fun removePlainHost(host: String) {
        plainHosts.remove(host)
    }

    fun add(f: NetworkFilter) {
        when {
            f.isException && f.pageFlags != 0 -> {
                pageExceptions.add(f)
                if (f.typeMask != 0) exceptions.add(f)
            }
            f.isException -> exceptions.add(f)
            else -> blocking.add(f)
        }
    }

    fun trimToSize() {
        blocking.trimToSize(); exceptions.trimToSize(); pageExceptions.trimToSize()
    }

    fun match(req: Request): MatchResult {
        val parents = DomainUtils.hostAndParents(req.host)
        val tokens = FilterParser.urlTokens(req.urlLower)
        val block = blocking.find(req, parents, tokens, true) ?: plainHostFilter(req, parents) ?: return MatchResult.None
        if (block.important) return MatchResult.Blocked(block)
        val exception = exceptions.find(req, parents, tokens, false)
        return if (exception != null) MatchResult.Allowed(exception) else MatchResult.Blocked(block)
    }

    private fun plainHostFilter(req: Request, parents: List<String>): NetworkFilter? {
        if (req.typeBits and RequestType.ALL_BUT_DOCUMENT == 0) return null
        for (h in parents) if (h in plainHosts) return FilterParser.parseNetwork("||$h^")
        return null
    }

    /** OR-ed [PageFlags] for the page (top level document) url */
    fun pageFlags(pageUrl: String): Int {
        val host = DomainUtils.hostOf(pageUrl)
        if (host.isEmpty()) return 0
        val req = Request(pageUrl, host, host, RequestType.ALL)
        var flags = 0
        for (f in pageExceptions.findAll(req, DomainUtils.hostAndParents(host), FilterParser.urlTokens(req.urlLower), false)) {
            flags = flags or f.pageFlags
        }
        return flags
    }
}
