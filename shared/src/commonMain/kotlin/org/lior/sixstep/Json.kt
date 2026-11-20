package org.lior.sixstep

/** A small JSON reader, enough for the app's data files. The Android app used org.json, which a browser does not have;
 *  this one is the same on every platform. Objects keep the order of the file. Numbers are Doubles. */
object Json {
    /** the value of a JSON text: a Map<String, Any?>, a List<Any?>, a String, a Double, a Boolean or null */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("something after the end of the value")
        return v
    }

    fun obj(text: String): JsonObj {
        @Suppress("UNCHECKED_CAST")
        return JsonObj(parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON: not an object"))
    }

    /** a number as org.json printed it when asked for a string: whole numbers without ".0" */
    fun numText(d: Double): String = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private class Parser(val s: String) {
        var i = 0

        fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what at character $i")

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(): Any? {
            if (i >= s.length) fail("the text ends where a value should be")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("unknown word")
            i += w.length
            return v
        }

        private fun num(): Double {
            val from = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(from, i).toDoubleOrNull() ?: fail("a number that cannot be read")
        }

        private fun str(): String {
            i++   // the opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("a string without its closing quote")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("a string that ends in a backslash")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("a \\u escape that is cut short")
                                sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: fail("a \\u escape that is not hexadecimal")).toChar())
                                i += 4
                            }
                            else -> fail("unknown escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++   // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) fail("a list without its closing bracket")
                when (s[i++]) { ',' -> {}; ']' -> return out; else -> fail("a comma or ] was expected") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++   // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("a name in quotes was expected")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("a colon was expected")
                i++
                ws(); out[k] = value(); ws()
                if (i >= s.length) fail("an object without its closing brace")
                when (s[i++]) { ',' -> {}; '}' -> return out; else -> fail("a comma or } was expected") }
            }
        }
    }
}

/** A JSON object with the lookups the data files need. The "opt" ones give the fallback when the name is missing or null. */
class JsonObj(val map: Map<String, Any?>) {
    val keys: Set<String> get() = map.keys
    fun has(k: String): Boolean = map[k] != null

    private fun need(k: String): Any = map[k] ?: throw IllegalArgumentException("JSON: no \"$k\"")
    private fun textOf(v: Any): String = when (v) { is String -> v; is Double -> Json.numText(v); else -> v.toString() }

    /** a string; a number or a boolean is given as its text, as org.json did */
    fun str(k: String): String = textOf(need(k))
    fun text(k: String): String = str(k)
    fun optStr(k: String, fallback: String = ""): String = map[k]?.let { textOf(it) } ?: fallback
    fun num(k: String): Double = toNum(need(k)) ?: throw IllegalArgumentException("JSON: \"$k\" is not a number")
    fun optNum(k: String, fallback: Double): Double = map[k]?.let { toNum(it) } ?: fallback
    fun int(k: String): Int = num(k).toInt()
    fun optInt(k: String, fallback: Int): Int = map[k]?.let { toNum(it) }?.toInt() ?: fallback
    fun optBool(k: String, fallback: Boolean): Boolean = when (val v = map[k]) { is Boolean -> v; "true" -> true; "false" -> false; else -> fallback }
    @Suppress("UNCHECKED_CAST")
    fun optObj(k: String): JsonObj? = (map[k] as? Map<String, Any?>)?.let { JsonObj(it) }
    fun obj(k: String): JsonObj = optObj(k) ?: throw IllegalArgumentException("JSON: \"$k\" is not an object")
    fun optArr(k: String): JsonArr? = (map[k] as? List<Any?>)?.let { JsonArr(it) }
    fun arr(k: String): JsonArr = optArr(k) ?: throw IllegalArgumentException("JSON: \"$k\" is not a list")

    private fun toNum(v: Any): Double? = when (v) { is Double -> v; is String -> v.toDoubleOrNull(); else -> null }
}

class JsonArr(val list: List<Any?>) {
    val size: Int get() = list.size
    fun num(i: Int): Double = (list[i] as? Double) ?: (list[i] as? String)?.toDoubleOrNull() ?: throw IllegalArgumentException("JSON: item $i is not a number")
    fun str(i: Int): String = when (val v = list[i]) { is String -> v; is Double -> Json.numText(v); null -> throw IllegalArgumentException("JSON: item $i is null"); else -> v.toString() }
    @Suppress("UNCHECKED_CAST")
    fun obj(i: Int): JsonObj = JsonObj(list[i] as? Map<String, Any?> ?: throw IllegalArgumentException("JSON: item $i is not an object"))
    fun arr(i: Int): JsonArr = JsonArr(list[i] as? List<Any?> ?: throw IllegalArgumentException("JSON: item $i is not a list"))
}

/** the strings of a list that may be missing */
fun JsonArr?.strings(): List<String> = if (this == null) emptyList() else (0 until size).map { str(it) }
