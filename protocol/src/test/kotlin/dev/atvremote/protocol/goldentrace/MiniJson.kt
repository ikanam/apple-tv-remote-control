package dev.atvremote.protocol.goldentrace

/**
 * Tiny dependency-free JSON parser, scoped to the golden-trace test helper.
 *
 * Supports objects, arrays, strings (with `\" \\ \/ \b \f \n \r \t \uXXXX`
 * escapes), numbers (parsed as Long when integral else Double), booleans and
 * null — sufficient for the synthetic fixtures `GoldenTraceGen` emits. Not a
 * general-purpose parser; intentionally minimal so the `:protocol` module needs
 * no JSON dependency.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.i == text.length) { "trailing JSON at offset ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't', 'f' -> bool()
                'n' -> nul()
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs(); expect(':')
                val v = value()
                m[k] = v
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> error("expected , or } at $i")
                }
            }
            return m
        }

        fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> error("expected , or ] at $i")
                }
            }
            return l
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4); i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("bad escape \\$e at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun bool(): Boolean =
            if (s.startsWith("true", i)) { i += 4; true }
            else if (s.startsWith("false", i)) { i += 5; false }
            else error("bad literal at $i")

        fun nul(): Any? {
            require(s.startsWith("null", i)) { "bad literal at $i" }
            i += 4
            return null
        }

        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }

        fun expect(c: Char) {
            require(s[i] == c) { "expected '$c' at $i, got '${s[i]}'" }
            i++
        }
    }
}
