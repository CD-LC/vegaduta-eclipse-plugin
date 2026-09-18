// A self-contained JSON reader/writer, ~the subset this plugin needs.
//
// Why hand-rolled rather than a library: an OSGi bundle can only use what the
// target platform resolves, and adding a JSON bundle to Require-Bundle makes
// installation fail on any Eclipse whose install does not already carry that
// exact bundle. The plugin's JSON surface is tiny and completely specified by
// the endpoints it calls (clients/shared/src/api/types.ts), so the trade is
// worth it - but ONLY because this file is unit-tested line by line
// (ai.vegaduta.ide.eclipse.tests/JsonTest).
//
// Parse result types, deliberately plain so callers need no wrapper classes:
//   object -> LinkedHashMap<String,Object>   array -> ArrayList<Object>
//   string -> String    number -> Long (integral) or Double    bool -> Boolean
//   null   -> Java null
//
// Failure posture matches the rest of this plugin and clients/shared: the
// PARSER throws JsonException (callers wrap it into a typed result), and the
// WRITER never throws.

package ai.vegaduta.ide.eclipse.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {
    }

    /** Thrown by {@link #parse} only. Never thrown by the writer. */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // --- parsing -------------------------------------------------------------

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("No JSON to parse");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("Trailing content at offset " + parser.pos);
        }
        return value;
    }

    /** Parses and requires an object. Used for every endpoint that returns one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** Parses and requires an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object value = parse(text);
        if (!(value instanceof List)) {
            throw new JsonException("Expected a JSON array");
        }
        return (List<Object>) value;
    }

    /** Parses an object, or an empty map when the text is absent or not an
     * object. For error bodies, where "the server sent HTML" must not become a
     * second failure on top of the first. */
    public static Map<String, Object> parseObjectOrEmpty(String text) {
        try {
            return parseObject(text);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    // --- typed field reads ---------------------------------------------------

    /** A string field, or null when absent or another type. Never throws. */
    public static String str(Map<String, Object> object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.get(key);
        return (value instanceof String s) ? s : null;
    }

    /** A string field, or {@code fallback} when absent/blank/another type. */
    public static String str(Map<String, Object> object, String key, String fallback) {
        String value = str(object, key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** A number field as a long, or {@code fallback}. Never throws. */
    public static long num(Map<String, Object> object, String key, long fallback) {
        if (object == null) {
            return fallback;
        }
        Object value = object.get(key);
        return (value instanceof Number n) ? n.longValue() : fallback;
    }

    /** A boolean field, or {@code fallback}. Never throws. */
    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        if (object == null) {
            return fallback;
        }
        Object value = object.get(key);
        return (value instanceof Boolean b) ? b : fallback;
    }

    /** A nested object field, or null. Never throws. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.get(key);
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }

    // --- writing -------------------------------------------------------------

    /** A mutable ordered map, so call sites read like the Kotlin buildJsonObject
     * blocks they mirror. Null values are DROPPED, not written as JSON null -
     * every optional field in this protocol means "absent" when unset. */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** An EXPLICIT JSON null, for the few protocol fields typed
     * {@code T | null} rather than optional (KnowledgeHit's). A plain Java
     * null in a map is dropped - see object(); this one is written. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** {@code value}, or {@link #NULL} when it is null. */
    public static Object orNull(Object value) {
        return value == null ? NULL : value;
    }

    /** Serializes any value produced by {@link #parse} or {@link #object}.
     * Never throws: an unsupported type is written as its toString(). */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null || value == NULL) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // JSON has no NaN/Infinity; null is the only honest thing to emit.
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else if (value instanceof Number n) {
            out.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    continue; // absent, not null - see object()
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(item, out);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    /** Escapes for a JSON string literal. Also escapes U+2028/U+2029 and '<',
     * because the result is embedded in a JavaScript string literal inside a
     * &lt;script&gt; element (WebviewPage / WebviewBridge) where a raw "</" would
     * end the element early and a raw line separator is a JS line break. */
    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // --- the parser ----------------------------------------------------------

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw new JsonException("Unexpected end of JSON");
            }
            return text.charAt(pos);
        }

        private void expect(char expected) {
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new JsonException("Expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }

        Object readValue() {
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void readLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new JsonException("Bad literal at offset " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            for (;;) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return result;
                }
                throw new JsonException("Expected ',' or '}' at offset " + pos);
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            for (;;) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return result;
                }
                throw new JsonException("Expected ',' or ']' at offset " + pos);
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            for (;;) {
                if (pos >= text.length()) {
                    throw new JsonException("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw new JsonException("Unterminated escape");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new JsonException("Truncated \\u escape");
                        }
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("Bad \\u escape: " + hex);
                        }
                    }
                    default -> throw new JsonException("Bad escape \\" + esc);
                }
            }
        }

        private Number readNumber() {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, pos);
            if (literal.isEmpty() || literal.equals("-") || literal.equals("+")) {
                throw new JsonException("Expected a value at offset " + start);
            }
            try {
                if (!floating) {
                    return Long.valueOf(literal);
                }
                return Double.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new JsonException("Bad number: " + literal);
            }
        }
    }
}
