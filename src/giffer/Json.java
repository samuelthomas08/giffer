package giffer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer (no external dependencies). Objects become maps, arrays lists, numbers Long/Double. */
final class Json {
    private Json() {}

    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) throw parser.error("Unerwartete Daten nach JSON");
        return value;
    }

    /** The value as a map, or null if it is not a JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** The value as a list, or null if it is not a JSON array. */
    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    static String asString(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    /** All non-null array elements converted to strings; empty if the value is not an array. */
    static List<String> asStringList(Object value) {
        List<String> strings = new ArrayList<>();
        List<Object> elements = asArray(value);
        if (elements != null) {
            for (Object element : elements) {
                if (element != null) strings.add(element.toString());
            }
        }
        return strings;
    }

    // ---------------------------------------------------------------- writer

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value, 0);
        return output.toString();
    }

    private static void writeValue(StringBuilder output, Object value, int indentLevel) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeQuoted(output, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(output, map, indentLevel);
        } else if (value instanceof Collection<?> collection) {
            writeCollection(output, collection, indentLevel);
        } else {
            writeQuoted(output, value.toString());
        }
    }

    private static void writeMap(StringBuilder output, Map<?, ?> map, int indentLevel) {
        if (map.isEmpty()) {
            output.append("{}");
            return;
        }
        output.append("{\n");
        int written = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            writeIndent(output, indentLevel + 1);
            writeQuoted(output, String.valueOf(entry.getKey()));
            output.append(": ");
            writeValue(output, entry.getValue(), indentLevel + 1);
            output.append(++written < map.size() ? ",\n" : "\n");
        }
        writeIndent(output, indentLevel);
        output.append('}');
    }

    private static void writeCollection(StringBuilder output, Collection<?> collection, int indentLevel) {
        if (collection.isEmpty()) {
            output.append("[]");
            return;
        }
        output.append("[\n");
        int written = 0;
        for (Object element : collection) {
            writeIndent(output, indentLevel + 1);
            writeValue(output, element, indentLevel + 1);
            output.append(++written < collection.size() ? ",\n" : "\n");
        }
        writeIndent(output, indentLevel);
        output.append(']');
    }

    private static void writeIndent(StringBuilder output, int indentLevel) {
        for (int level = 0; level < indentLevel; level++) output.append("  ");
    }

    private static void writeQuoted(StringBuilder output, String text) {
        output.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        output.append('"');
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        RuntimeException error(String message) {
            return new IllegalArgumentException(message + " (Position " + position + ")");
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        char peek() {
            if (position >= text.length()) throw error("Unerwartetes Ende");
            return text.charAt(position);
        }

        void expect(String literal) {
            if (!text.startsWith(literal, position)) throw error("'" + literal + "' erwartet");
            position += literal.length();
        }

        Object readValue() {
            switch (peek()) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> members = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return members;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (peek() != ':') throw error("':' erwartet");
                position++;
                skipWhitespace();
                members.put(key, readValue());
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == '}') return members;
                if (separator != ',') throw error("',' oder '}' erwartet");
            }
        }

        List<Object> readArray() {
            List<Object> elements = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return elements;
            }
            while (true) {
                skipWhitespace();
                elements.add(readValue());
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == ']') return elements;
                if (separator != ',') throw error("',' oder ']' erwartet");
            }
        }

        String readString() {
            if (peek() != '"') throw error("String erwartet");
            position++;
            StringBuilder result = new StringBuilder();
            while (true) {
                char character = peek();
                position++;
                if (character == '"') return result.toString();
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                char escaped = peek();
                position++;
                switch (escaped) {
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    case 'r' -> result.append('\r');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) throw error("Ungültiges \\u-Escape");
                        result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> result.append(escaped); // " \ /
                }
            }
        }

        Object readNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            if (start == position) throw error("Unerwartetes Zeichen '" + text.charAt(position) + "'");
            String number = text.substring(start, position);
            if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
            return Long.parseLong(number);
        }
    }
}
