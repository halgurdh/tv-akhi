package com.phlox.tvwebbrowser.adblock

internal object Json {
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")
}

/**
 * Parses uBO cosmetic filter bodies. Result is either plain CSS selector (hidden with stylesheet),
 * a CSS rule (`:style()`), or a procedural filter serialized to JSON for the content script.
 */
object CosmeticParser {
    sealed class Parsed {
        class Hide(val selector: String) : Parsed()
        class CssRule(val rule: String) : Parsed()
        class Procedural(val json: String) : Parsed()
    }

    private val OPERATORS = hashSetOf(
        "has-text", "-abp-contains", "contains", "upward", "xpath", "matches-css", "matches-css-before",
        "matches-css-after", "min-text-length", "matches-path", "matches-attr", "matches-media", "others",
        "watch-attr", "if", "if-not", "-abp-has", "has", "not", "remove", "remove-attr", "remove-class", "style"
    )
    /** these are native CSS when their argument is plain CSS */
    private val NATIVE_WHEN_PLAIN = hashSetOf("has", "not", "-abp-has", "if", "if-not")
    private val ACTIONS = hashSetOf("remove", "remove-attr", "remove-class", "style")

    private class Op(val name: String, val arg: String, val start: Int, val end: Int)

    /** Finds top-level `:name(arg)` operators */
    private fun findOperators(s: String): List<Op>? {
        val ops = ArrayList<Op>()
        var i = 0
        var bracket = 0
        var paren = 0
        var quote = 0.toChar()
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
                i++; continue
            }
            when (c) {
                '\\' -> { i += 2; continue }
                '"', '\'' -> quote = c
                '[' -> bracket++
                ']' -> bracket--
                '(' -> paren++
                ')' -> paren--
                ':' -> if (bracket == 0 && paren == 0) {
                    var j = i + 1
                    if (j < s.length && s[j] == ':') { i += 2; continue } //pseudo-element
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-')) j++
                    val name = s.substring(i + 1, j).lowercase()
                    if (j < s.length && s[j] == '(' && name in OPERATORS) {
                        val close = matchingParen(s, j) ?: return null
                        val arg = s.substring(j + 1, close)
                        if (name !in NATIVE_WHEN_PLAIN || containsProcedural(arg)) {
                            ops.add(Op(name, arg, i, close + 1))
                        }
                        i = close + 1
                        continue
                    }
                }
            }
            i++
        }
        return ops
    }

    private fun matchingParen(s: String, open: Int): Int? {
        var depth = 0
        var i = open
        var quote = 0.toChar()
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
            } else when (c) {
                '\\' -> i++
                '"', '\'' -> quote = c
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun containsProcedural(s: String): Boolean {
        val ops = findOperators(s) ?: return true
        return ops.isNotEmpty()
    }

    private fun canonicalOp(name: String) = when (name) {
        "-abp-contains", "contains" -> "has-text"
        "-abp-has", "if" -> "has"
        "if-not" -> "not"
        else -> name
    }

    /** Serializes selector (without action) to JSON task, or null if unsupported */
    private fun proceduralJson(selector: String): String? {
        val ops = findOperators(selector) ?: return null
        val sb = StringBuilder()
        val base = if (ops.isEmpty()) selector else selector.substring(0, ops[0].start)
        sb.append("{\"s\":").append(Json.str(base.trim())).append(",\"t\":[")
        var first = true
        for ((index, op) in ops.withIndex()) {
            val name = canonicalOp(op.name)
            if (name in ACTIONS) return null //action in the middle is not supported
            if (!first) sb.append(',')
            first = false
            if (name == "has" || name == "not") {
                val nested = proceduralJson(op.arg.trim()) ?: return null
                sb.append("[").append(Json.str(name)).append(",").append(nested).append("]")
            } else {
                sb.append("[").append(Json.str(name)).append(",").append(Json.str(op.arg)).append("]")
            }
            //plain css between this operator and the next one
            val nextStart = if (index + 1 < ops.size) ops[index + 1].start else selector.length
            val between = selector.substring(op.end, nextStart)
            if (between.isNotBlank()) {
                sb.append(",[\"spath\",").append(Json.str(between)).append("]")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    fun parse(body: String): Parsed? {
        val ops = findOperators(body) ?: return null
        if (ops.isEmpty()) return Parsed.Hide(body)
        val last = ops.last()
        val lastName = canonicalOp(last.name)
        var selector = body
        var action: String? = null
        if (lastName in ACTIONS) {
            if (body.substring(last.end).isNotBlank()) return null
            selector = body.substring(0, last.start)
            if (selector.isBlank()) return null
            if (lastName == "style") {
                val style = last.arg
                if (style.contains('{') || style.contains('}') || style.contains("url(", ignoreCase = true)) return null
                if (ops.size == 1) return Parsed.CssRule("$selector{$style}")
                action = "[\"style\"," + Json.str(style) + "]"
            } else {
                action = "[" + Json.str(lastName) + "," + Json.str(last.arg) + "]"
            }
        }
        val task = proceduralJson(selector) ?: return null
        val json = if (action != null) task.substring(0, task.length - 1) + ",\"a\":" + action + "}" else task
        return Parsed.Procedural(json)
    }
}

object ScriptletParser {
    private val ALIASES = mapOf(
        "set" to "set-constant",
        "trusted-set" to "trusted-set-constant",
        "aopr" to "abort-on-property-read",
        "aopw" to "abort-on-property-write",
        "acs" to "abort-current-script", "acis" to "abort-current-script",
        "abort-current-inline-script" to "abort-current-script",
        "aost" to "abort-on-stack-trace",
        "nowoif" to "no-window-open-if", "prevent-window-open" to "no-window-open-if",
        "window.open-defuser" to "no-window-open-if",
        "nostif" to "no-setTimeout-if", "prevent-setTimeout" to "no-setTimeout-if",
        "setTimeout-defuser" to "no-setTimeout-if",
        "nosiif" to "no-setInterval-if", "prevent-setInterval" to "no-setInterval-if",
        "setInterval-defuser" to "no-setInterval-if",
        "aeld" to "addEventListener-defuser", "prevent-addEventListener" to "addEventListener-defuser",
        "rmnt" to "remove-node-text",
        "rpnt" to "trusted-replace-node-text", "replace-node-text" to "trusted-replace-node-text",
        "trusted-rpnt" to "trusted-replace-node-text",
        "prevent-fetch" to "no-fetch-if",
        "prevent-xhr" to "no-xhr-if",
        "nano-sib" to "nano-setInterval-booster", "adjust-setInterval" to "nano-setInterval-booster",
        "nano-stb" to "nano-setTimeout-booster", "adjust-setTimeout" to "nano-setTimeout-booster",
        "prevent-eval-if" to "noeval-if",
        "jpfr" to "json-prune-fetch-response",
        "jpxr" to "json-prune-xhr-response",
        "trusted-rpfr" to "trusted-replace-fetch-response",
        "trusted-rpxr" to "trusted-replace-xhr-response",
        "ra" to "remove-attr",
        "rc" to "remove-class",
        "cookie-remover" to "remove-cookie"
    )

    fun canonicalName(name: String): String {
        val n = name.removeSuffix(".js")
        return ALIASES[n] ?: n
    }

    /** `+js(name, arg1, 'arg, 2')` -> [name, arg1, "arg, 2"] or null */
    fun parse(body: String): List<String>? {
        if (!body.startsWith("+js(") || !body.endsWith(")")) return null
        val inner = body.substring(4, body.length - 1)
        if (inner.isBlank()) return emptyList()
        val args = ArrayList<String>()
        var i = 0
        while (i <= inner.length) {
            while (i < inner.length && inner[i] == ' ') i++
            val sb = StringBuilder()
            if (i < inner.length && (inner[i] == '\'' || inner[i] == '"' || inner[i] == '`')) {
                val q = inner[i]
                var j = i + 1
                var closed = -1
                while (j < inner.length) {
                    if (inner[j] == '\\' && j + 1 < inner.length && inner[j + 1] == q) { sb.append(q); j += 2; continue }
                    if (inner[j] == q) { closed = j; break }
                    sb.append(inner[j]); j++
                }
                if (closed >= 0) {
                    //quoted arg must be followed by comma or end
                    var k = closed + 1
                    while (k < inner.length && inner[k] == ' ') k++
                    if (k >= inner.length || inner[k] == ',') {
                        args.add(sb.toString())
                        i = k + 1
                        if (k >= inner.length) break
                        continue
                    }
                }
                sb.setLength(0) //not a properly quoted arg, read it raw
            }
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length && inner[i + 1] == ',') { sb.append(','); i += 2; continue }
                if (c == ',') break
                sb.append(c); i++
            }
            args.add(sb.toString().trim())
            if (i >= inner.length) break
            i++ //skip comma
            if (i == inner.length) { args.add(""); break }
        }
        if (args.isEmpty()) return null
        args[0] = canonicalName(args[0])
        return args
    }

    fun isTrustedOnly(canonicalName: String) = canonicalName.startsWith("trusted-")
}
