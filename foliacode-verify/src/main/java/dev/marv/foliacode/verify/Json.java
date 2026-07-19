package dev.marv.foliacode.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader used to parse PaperMC API responses.
 *
 * <p>This module deliberately avoids a JSON library dependency so that FoliaCode never
 * conflicts with the dependencies of the plugins it analyses. The parser is intentionally
 * small and strict: it understands exactly the JSON grammar and nothing more.</p>
 *
 * <p>A structural parser is used rather than regular expressions because the build listing
 * nests {@code commits} arrays whose objects also carry {@code sha} and {@code time} keys.
 * Pattern matching across that structure is fragile; walking it is not.</p>
 *
 * <p>Parsed values map to Java as follows: object to {@link LinkedHashMap} (insertion order
 * preserved), array to {@link List}, string to {@link String}, number to {@link Double},
 * boolean to {@link Boolean}, and {@code null} to {@code null}.</p>
 */
final class Json {

    private final String source;
    private int cursor;

    private Json(String source) {
        this.source = source;
    }

    /**
     * Parses a complete JSON document.
     *
     * @param text the document to parse; may be {@code null}
     * @return the parsed value, or {@code null} if the input is null, blank or malformed
     */
    static Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Json parser = new Json(text);
            parser.skipWhitespace();
            Object value = parser.readValue();
            parser.skipWhitespace();
            // Trailing content means the document is not what we think it is; reject it.
            return parser.cursor == text.length() ? value : null;
        } catch (RuntimeException e) {
            // A malformed response must not crash the tool, it must degrade to "unknown".
            return null;
        }
    }

    /**
     * Interprets a parsed value as an object.
     *
     * @param value the value to interpret; may be {@code null}
     * @return the map, or an empty map when the value is not an object
     */
    static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    /**
     * Interprets a parsed value as an array.
     *
     * @param value the value to interpret; may be {@code null}
     * @return the list, or an empty list when the value is not an array
     */
    static List<Object> asArray(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    /**
     * Interprets a parsed value as a string.
     *
     * @param value the value to interpret; may be {@code null}
     * @return the string, or {@code null} when the value is not a string
     */
    static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * Interprets a parsed value as an integer.
     *
     * @param value        the value to interpret; may be {@code null}
     * @param defaultValue the value returned when interpretation fails
     * @return the integer, or {@code defaultValue} when the value is not a number
     */
    static int asInt(Object value, int defaultValue) {
        return value instanceof Double number ? (int) number.doubleValue() : defaultValue;
    }

    /**
     * Reads a nested value by walking object keys.
     *
     * @param root the value to start from; may be {@code null}
     * @param path the object keys to follow in order
     * @return the value at the path, or {@code null} when any step is missing
     */
    static Object path(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            cursor++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw new IllegalStateException("Expected ',' or '}' at index " + (cursor - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            cursor++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw new IllegalStateException("Expected ',' or ']' at index " + (cursor - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return builder.toString();
            }
            if (c != '\\') {
                builder.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    builder.append((char) Integer.parseInt(source.substring(cursor, cursor + 4), 16));
                    cursor += 4;
                }
                default -> throw new IllegalStateException("Invalid escape '\\" + escape + "'");
            }
        }
    }

    private Double readNumber() {
        int start = cursor;
        while (cursor < source.length() && "+-.eE0123456789".indexOf(source.charAt(cursor)) >= 0) {
            cursor++;
        }
        if (start == cursor) {
            throw new IllegalStateException("Expected a value at index " + start);
        }
        return Double.valueOf(source.substring(start, cursor));
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, cursor)) {
            throw new IllegalStateException("Expected '" + literal + "' at index " + cursor);
        }
        cursor += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
    }

    private void expect(char expected) {
        char actual = next();
        if (actual != expected) {
            throw new IllegalStateException("Expected '" + expected + "' but found '" + actual + "'");
        }
    }

    private char peek() {
        if (cursor >= source.length()) {
            throw new IllegalStateException("Unexpected end of input");
        }
        return source.charAt(cursor);
    }

    private char next() {
        char c = peek();
        cursor++;
        return c;
    }
}
