package io.monadcount.csiq.format

/**
 * A minimal JSON reader for the session block.
 *
 * The format's promise is that a capture is interpretable by someone who has
 * only the file and the specification. A parser of 150 lines keeps the format
 * layer free of any dependency, so that promise holds for this reader too.
 *
 * The session block is opaque to the container and its schema is permissive, so
 * this deliberately does not model `csid-session/1`. It gives typed access to
 * whatever was written, and a viewer renders the tree it finds.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val value: Double, val raw: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
}

/** `a.b.c` lookup. Returns null at the first missing or non-object step. */
fun JsonValue?.path(vararg keys: String): JsonValue? {
    var cur = this
    for (k in keys) {
        cur = (cur as? JsonValue.Obj)?.fields?.get(k) ?: return null
    }
    return cur
}

fun JsonValue?.asString(): String? = when (this) {
    is JsonValue.Str -> value
    is JsonValue.Num -> raw
    is JsonValue.Bool -> value.toString()
    else -> null
}

/**
 * The value as a 64-bit integer.
 *
 * Parsed from the literal text, not from the `Double`. A session block carries
 * nanosecond timestamps and byte counts above 2^53, and routing those through a
 * double silently rounds them: `unix_ts_ns` 1785161577060934856 comes back as
 * ...912. Returns null when the literal is not an integer.
 */
fun JsonValue?.asLong(): Long? {
    val n = this as? JsonValue.Num ?: return null
    return n.raw.toLongOrNull() ?: n.value.takeIf { it == Math.rint(it) }?.toLong()
}

fun JsonValue?.asDouble(): Double? = (this as? JsonValue.Num)?.value

class JsonParseException(message: String) : Exception(message)

object Json {
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos != text.length) throw JsonParseException("trailing content at offset ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): JsonValue {
            if (pos >= s.length) throw JsonParseException("unexpected end of input")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(str())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> num()
            }
        }

        fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) throw JsonParseException("bad literal at offset $pos")
            pos += word.length
            return v
        }

        fun obj(): JsonValue {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                fields[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(fields) }
                    else -> throw JsonParseException("expected , or } at offset $pos")
                }
            }
        }

        fun arr(): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                skipWs()
                items.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> throw JsonParseException("expected , or ] at offset $pos")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonParseException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("short \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("bad escape \\$e at offset ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            val d = raw.toDoubleOrNull() ?: throw JsonParseException("bad number '$raw' at offset $start")
            return JsonValue.Num(d, raw)
        }

        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        fun expect(c: Char) {
            if (peek() != c) throw JsonParseException("expected '$c' at offset $pos")
            pos++
        }
    }
}
