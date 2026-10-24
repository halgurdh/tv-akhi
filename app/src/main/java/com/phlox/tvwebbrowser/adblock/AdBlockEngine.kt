package com.phlox.tvwebbrowser.adblock

/**
 * uBlock Origin compatible (subset) filtering engine: network filters, cosmetic filters
 * (generic/specific/procedural) and scriptlets. Immutable after [Builder.build], safe to use from any thread.
 */
class AdBlockEngine private constructor(
    val network: NetworkEngine,
    private val lowGeneric: HashMap<String, ArrayList<String>>,
    private val highGenericCss: String,
    private val highGenericSelectors: HashSet<String>,
    private val genericExceptions: HashSet<String>,
    private val specific: HashMap<String, ArrayList<Entry>>,
    private val specificExceptions: HashMap<String, ArrayList<Entry>>,
    private val genericWithExclusions: ArrayList<Entry>,
    val stats: String
) {
    internal class Entry(val kind: Int, val value: String, val excluded: Array<String>?) {
        companion object {
            const val HIDE = 0
            const val CSS_RULE = 1
            const val PROCEDURAL = 2
            const val SCRIPTLET = 3
        }
    }

    private fun lookupKeys(host: String): List<String> {
        val keys = ArrayList<String>(DomainUtils.hostAndParents(host))
        keys.addAll(DomainUtils.entities(host))
        return keys
    }

    private fun collect(map: HashMap<String, ArrayList<Entry>>, host: String): List<Entry> {
        val result = ArrayList<Entry>()
        for (key in lookupKeys(host)) {
            map[key]?.let { list ->
                for (e in list) {
                    if (e.excluded == null || e.excluded.none { DomainUtils.matches(host, it) }) result.add(e)
                }
            }
        }
        return result
    }

    /** JSON with everything the content script needs for this page/frame */
    fun pageData(frameUrl: String, pageFlags: Int): String {
        val host = DomainUtils.hostOf(frameUrl)
        if (host.isEmpty() || pageFlags and PageFlags.DOCUMENT != 0) return "null"
        val noCosmetic = pageFlags and PageFlags.ELEMHIDE != 0
        val noGeneric = noCosmetic || pageFlags and PageFlags.GENERICHIDE != 0
        val noSpecific = noCosmetic || pageFlags and PageFlags.SPECIFICHIDE != 0

        val exceptions = collect(specificExceptions, host)
        val excludedValues = HashSet<String>()
        var allScriptletsDisabled = false
        for (e in exceptions) {
            if (e.kind == Entry.SCRIPTLET && e.value == "[]") allScriptletsDisabled = true
            excludedValues.add(e.value)
        }

        val css = StringBuilder()
        val procedural = ArrayList<String>()
        val scriptlets = ArrayList<String>()
        val entries = collect(specific, host) + genericWithExclusions.filter { e ->
            e.excluded == null || e.excluded.none { DomainUtils.matches(host, it) }
        }
        val seen = HashSet<String>()
        for (e in entries) {
            if (e.value in excludedValues || !seen.add(e.value)) continue
            when (e.kind) {
                Entry.SCRIPTLET -> if (!allScriptletsDisabled) scriptlets.add(e.value)
                Entry.HIDE -> if (!noSpecific && e.value !in genericExceptions) css.append(e.value).append("{display:none!important}\n")
                Entry.CSS_RULE -> if (!noSpecific) css.append(e.value).append('\n')
                Entry.PROCEDURAL -> if (!noSpecific) procedural.add(e.value)
            }
        }
        if (!noGeneric) {
            if (excludedValues.any { it in highGenericSelectors }) {
                for (s in highGenericSelectors) if (s !in excludedValues) css.append(s).append("{display:none!important}\n")
            } else {
                css.append(highGenericCss)
            }
        }
        return "{\"css\":" + Json.str(css.toString()) +
                ",\"procedural\":" + Json.arr(procedural) +
                ",\"scriptlets\":" + Json.arr(scriptlets) +
                ",\"generic\":" + (!noGeneric) + "}"
    }

    /** CSS for generic selectors keyed by ids/classes (`#id`, `.class`) found on the page */
    fun genericCss(frameUrl: String, keys: List<String>): String {
        val host = DomainUtils.hostOf(frameUrl)
        var excluded: Set<String>? = null
        val sb = StringBuilder()
        for (key in keys) {
            val selectors = lowGeneric[key] ?: continue
            if (excluded == null) excluded = collect(specificExceptions, host).map { it.value }.toHashSet()
            for (s in selectors) {
                if (s in excluded) continue
                sb.append(s).append("{display:none!important}\n")
            }
        }
        return sb.toString()
    }

    class Builder {
        private val network = NetworkEngine()
        private val lowGeneric = HashMap<String, ArrayList<String>>()
        private val highGeneric = LinkedHashSet<String>()
        private val genericExceptions = HashSet<String>()
        private val specific = HashMap<String, ArrayList<Entry>>()
        private val specificExceptions = HashMap<String, ArrayList<Entry>>()
        private val genericWithExclusions = ArrayList<Entry>()
        private val pendingNetwork = ArrayList<NetworkFilter>()
        private val badFilters = HashSet<String>()
        private var lines = 0
        private var cosmeticCount = 0
        private var scriptletCount = 0
        private var skipped = 0

        /** [trusted] lists may use `trusted-*` scriptlets (uBO's own lists) */
        fun addList(text: String, trusted: Boolean) {
            for (line in FilterParser.preprocess(text.lineSequence())) {
                lines++
                addFilter(line, trusted)
            }
        }

        fun addFilter(line: String, trusted: Boolean) {
            plainHostOf(line)?.let { network.addPlainHost(it); return }
            val parsed = try {
                FilterParser.parseLine(line)
            } catch (e: Exception) {
                null
            }
            when (parsed) {
                is NetworkFilter -> {
                    FilterParser.badfilterTarget(line)?.let { badFilters.add(it); return }
                    pendingNetwork.add(parsed)
                }
                is CosmeticLine -> addCosmetic(parsed, trusted)
                else -> if (line.isNotEmpty() && !line.startsWith('!')) skipped++
            }
        }

        private fun addCosmetic(c: CosmeticLine, trusted: Boolean) {
            val entryKind: Int
            val value: String
            if (c.body.startsWith("+js(")) {
                val args = ScriptletParser.parse(c.body) ?: run { skipped++; return }
                if (args.isNotEmpty() && ScriptletParser.isTrustedOnly(args[0]) && !trusted && !c.isException) {
                    skipped++; return
                }
                entryKind = Entry.SCRIPTLET
                value = Json.arr(args.map { Json.str(it) })
                scriptletCount++
            } else {
                when (val p = CosmeticParser.parse(c.body)) {
                    null -> { skipped++; return }
                    is CosmeticParser.Parsed.Hide -> { entryKind = Entry.HIDE; value = p.selector }
                    is CosmeticParser.Parsed.CssRule -> { entryKind = Entry.CSS_RULE; value = p.rule }
                    is CosmeticParser.Parsed.Procedural -> { entryKind = Entry.PROCEDURAL; value = p.json }
                }
                cosmeticCount++
            }

            val domains = c.domains
            if (domains == null || domains.include.isEmpty()) {
                //generic filter
                if (c.isException) {
                    if (domains == null) genericExceptions.add(value)
                    return
                }
                if (domains != null) {
                    genericWithExclusions.add(Entry(entryKind, value, domains.exclude))
                    return
                }
                when (entryKind) {
                    Entry.HIDE -> addGenericHide(value)
                    Entry.SCRIPTLET, Entry.CSS_RULE, Entry.PROCEDURAL -> skipped++ //uBO doesn't apply these generically either
                }
                return
            }
            val target = if (c.isException) specificExceptions else specific
            val entry = Entry(entryKind, value, if (domains.exclude.isEmpty()) null else domains.exclude)
            for (d in domains.include) target.getOrPut(d) { ArrayList(2) }.add(entry)
        }

        private fun addGenericHide(selector: String) {
            val m = LOW_GENERIC_KEY.find(selector)
            if (m != null) {
                lowGeneric.getOrPut(m.value) { ArrayList(1) }.add(selector)
            } else {
                highGeneric.add(selector)
            }
        }

        fun build(): AdBlockEngine {
            for (f in pendingNetwork) {
                if (f.raw in badFilters) continue
                network.add(f)
            }
            for (bad in badFilters) plainHostOf(bad)?.let { network.removePlainHost(it) }
            network.trimToSize()
            for (s in genericExceptions) {
                highGeneric.remove(s)
                LOW_GENERIC_KEY.find(s)?.let { lowGeneric[it.value]?.remove(s) }
            }
            val highCss = StringBuilder()
            for (s in highGeneric) highCss.append(s).append("{display:none!important}\n")
            val stats = "lines=$lines network=${network.size} cosmetic=$cosmeticCount scriptlets=$scriptletCount " +
                    "lowGeneric=${lowGeneric.size} highGeneric=${highGeneric.size} skipped=$skipped"
            return AdBlockEngine(network, lowGeneric, highCss.toString(), HashSet(highGeneric), genericExceptions,
                specific, specificExceptions, genericWithExclusions, stats)
        }

        companion object {
            /** `||host^` -> host, fast path that skips full parsing */
            private fun plainHostOf(line: String): String? {
                if (line.length < 5 || !line.startsWith("||") || line[line.length - 1] != '^') return null
                for (i in 2 until line.length - 1) {
                    val c = line[i]
                    if (!((c in 'a'..'z') || (c in '0'..'9') || c == '.' || c == '-' || c == '_')) return null
                }
                return line.substring(2, line.length - 1)
            }

            /** generic selectors starting with simple id or class are only injected if page has such id/class */
            private val LOW_GENERIC_KEY = Regex("""^[#.][A-Za-z0-9_-]+(?=$|[\[:.#\s>+~])""")
        }
    }
}
