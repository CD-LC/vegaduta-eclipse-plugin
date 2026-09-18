// The hand-rolled JSON layer is only defensible because of this file - see
// Json's header. Covers the wire shapes the plugin actually meets plus the
// escaping rules that matter when the output is embedded in a <script>.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class JsonTest {

    @Test
    @DisplayName("parses the scalar types")
    void scalars() {
        assertEquals("hi", Json.parse("\"hi\""));
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(1.5, (Double) Json.parse("1.5"), 0.0);
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    @DisplayName("parses nested objects and arrays, preserving key order")
    void nested() {
        Map<String, Object> o = Json.parseObject(
                "{\"a\":1,\"b\":[1,2,{\"c\":\"x\"}],\"d\":{\"e\":null}}");
        assertEquals(List.of("a", "b", "d"), List.copyOf(o.keySet()));
        assertEquals(1L, o.get("a"));
        List<?> b = (List<?>) o.get("b");
        assertEquals(3, b.size());
        assertEquals("x", ((Map<?, ?>) b.get(2)).get("c"));
        assertTrue(((Map<?, ?>) o.get("d")).containsKey("e"));
        assertNull(((Map<?, ?>) o.get("d")).get("e"));
    }

    @Test
    @DisplayName("handles whitespace, empty containers and escapes")
    void whitespaceAndEscapes() {
        assertEquals(Map.of(), Json.parseObject("  {\n\t}  "));
        assertEquals(List.of(), Json.parseArray(" [ ] "));
        assertEquals("a\"b\\c\nd\te", Json.parse("\"a\\\"b\\\\c\\nd\\te\""));
        assertEquals("/", Json.parse("\"\\/\""));
        assertEquals("\u00e9\u20ac", Json.parse("\"\\u00e9\\u20ac\""));
    }

    @Test
    @DisplayName("rejects malformed input rather than guessing")
    void rejectsMalformed() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{} trailing"));
        assertThrows(Json.JsonException.class, () -> Json.parse(null));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1]"));
        assertThrows(Json.JsonException.class, () -> Json.parseArray("{}"));
    }

    @Test
    @DisplayName("parseObjectOrEmpty never throws - error bodies are not always JSON")
    void parseObjectOrEmptyIsTotal() {
        assertEquals(Map.of(), Json.parseObjectOrEmpty("<html>502</html>"));
        assertEquals(Map.of(), Json.parseObjectOrEmpty(null));
        assertEquals(Map.of(), Json.parseObjectOrEmpty(""));
        assertEquals("x", Json.str(Json.parseObjectOrEmpty("{\"a\":\"x\"}"), "a"));
    }

    @Test
    @DisplayName("typed field reads never throw on a wrong type")
    void typedReadsAreTotal() {
        Map<String, Object> o = Json.parseObject(
                "{\"s\":\"x\",\"n\":5,\"b\":true,\"o\":{\"k\":1},\"wrong\":[]}");
        assertEquals("x", Json.str(o, "s"));
        assertNull(Json.str(o, "n"));
        assertNull(Json.str(o, "missing"));
        assertEquals("fb", Json.str(o, "missing", "fb"));
        assertEquals("fb", Json.str(Json.parseObject("{\"s\":\"  \"}"), "s", "fb"));
        assertEquals(5L, Json.num(o, "n", -1));
        assertEquals(-1L, Json.num(o, "s", -1));
        assertTrue(Json.bool(o, "b", false));
        assertFalse(Json.bool(o, "s", false));
        assertEquals(1L, Json.obj(o, "o").get("k"));
        assertNull(Json.obj(o, "wrong"));
        assertNull(Json.str(null, "s"));
    }

    @Test
    @DisplayName("writes objects and drops null values rather than emitting null")
    void writesDroppingNulls() {
        Map<String, Object> o = Json.object();
        o.put("a", "x");
        o.put("b", null);
        o.put("c", 3);
        o.put("d", true);
        assertEquals("{\"a\":\"x\",\"c\":3,\"d\":true}", Json.write(o));
    }

    @Test
    @DisplayName("writes integral doubles without a decimal point")
    void writesIntegralDoubles() {
        assertEquals("3", Json.write(3.0));
        assertEquals("3.5", Json.write(3.5));
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("escapes what would break a <script> embedding")
    void escapesForScriptEmbedding() {
        // "</script>" must not survive as literal text - the '<' is escaped.
        String written = Json.write("</script><b>");
        assertFalse(written.contains("</script>"), written);
        assertTrue(written.contains("\\u003c"), written);
        assertEquals("\"a\\u2028b\"", Json.write("a\u2028b"));
        assertEquals("\"\\u0000\"", Json.write("\u0000"));
    }

    @Test
    @DisplayName("write then parse round-trips the strings the protocol carries")
    void roundTrip() {
        String tricky = "line1\nline2\ttab \"quoted\" back\\slash </script> \u00e9 \u2028";
        Map<String, Object> o = Json.object();
        o.put("delta", tricky);
        assertEquals(tricky, Json.str(Json.parseObject(Json.write(o)), "delta"));
    }

    @Test
    @DisplayName("double-encoding produces a valid JS string literal argument")
    void doubleEncoding() {
        // This is exactly what WebviewPage.deliverScript does.
        Map<String, Object> message = Json.object();
        message.put("type", "chat.chunk");
        message.put("delta", "a\nb");
        String inner = Json.write(message);
        String literal = Json.write(inner);
        assertEquals(inner, Json.parse(literal));
    }
}
