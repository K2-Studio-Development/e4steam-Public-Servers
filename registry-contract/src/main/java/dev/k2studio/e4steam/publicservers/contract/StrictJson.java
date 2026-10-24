package dev.k2studio.e4steam.publicservers.contract;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic JSON codec with duplicate-key, UTF-8, depth and size rejection. */
public final class StrictJson {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VALUES = 1_024;
    private static final int MAX_STRING = 4_096;

    private StrictJson() {
    }

    public static Object parse(byte[] input, int maximumBytes) {
        if (input == null) throw new NullPointerException("input");
        if (input.length == 0 || input.length > maximumBytes) {
            throw new IllegalArgumentException("JSON body has an invalid size");
        }
        String text;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input));
            text = decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("JSON body is not valid UTF-8", failure);
        }
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.whitespace();
        if (!parser.end()) throw parser.error("trailing JSON data");
        return result;
    }

    public static byte[] write(Object value, int maximumBytes) {
        StringBuilder output = new StringBuilder();
        Writer.write(value, output, 0);
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("JSON response is too large");
        return bytes;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + " must be an object");
        return (Map<String, Object>) map;
    }

    public static List<Object> array(Object value, String field) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + " must be an array");
        return new ArrayList<>(list);
    }

    public static String string(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be a string");
        return text;
    }

    public static String optionalString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be a string");
        return text;
    }

    public static long integer(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof Long number)) throw new IllegalArgumentException(field + " must be an integer");
        return number;
    }

    public static boolean bool(Map<String, Object> object, String field) {
        Object value = required(object, field);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(field + " must be a boolean");
        return flag;
    }

    private static Object required(Map<String, Object> object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return object.get(field);
    }

    private static final class Parser {
        private final String text;
        private int index;
        private int values;

        private Parser(String text) { this.text = text; }
        private boolean end() { return index == text.length(); }
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
        private void whitespace() {
            while (!end()) {
                char current = text.charAt(index);
                if (current != ' ' && current != '\n' && current != '\r' && current != '\t') return;
                index++;
            }
        }
        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nesting is too deep");
            if (++values > MAX_VALUES) throw error("JSON contains too many values");
            whitespace();
            if (end()) throw error("missing JSON value");
            return switch (text.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String, Object> object(int depth) {
            index++;
            LinkedHashMap<String, Object> object = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return object;
            while (true) {
                whitespace();
                if (end() || text.charAt(index) != '"') throw error("object key must be a string");
                String key = string();
                if (object.containsKey(key)) throw error("duplicate object key");
                whitespace();
                require(':');
                object.put(key, value(depth));
                whitespace();
                if (consume('}')) return object;
                require(',');
            }
        }
        private List<Object> array(int depth) {
            index++;
            ArrayList<Object> array = new ArrayList<>();
            whitespace();
            if (consume(']')) return array;
            while (true) {
                array.add(value(depth));
                whitespace();
                if (consume(']')) return array;
                require(',');
            }
        }
        private String string() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    if (result.length() > MAX_STRING) throw error("JSON string is too long");
                    return result.toString();
                }
                if (current < 0x20) throw error("control character in JSON string");
                if (current != '\\') {
                    if (Character.isHighSurrogate(current)) {
                        if (end() || !Character.isLowSurrogate(text.charAt(index))) {
                            throw error("unpaired surrogate");
                        }
                        result.append(current).append(text.charAt(index++));
                    } else if (Character.isLowSurrogate(current)) {
                        throw error("unpaired surrogate");
                    } else {
                        result.append(current);
                    }
                    continue;
                }
                if (end()) throw error("unterminated escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw error("unknown escape");
                }
            }
            throw error("unterminated string");
        }
        private char unicodeEscape() {
            if (index + 4 > text.length()) throw error("short Unicode escape");
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw error("invalid Unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }
        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, index)) throw error("invalid literal");
            index += expected.length();
            return value;
        }
        private Long number() {
            int start = index;
            if (consume('-') && end()) throw error("incomplete number");
            if (consume('0')) {
                if (!end() && Character.isDigit(text.charAt(index))) throw error("leading zero");
            } else {
                int digits = 0;
                while (!end() && Character.isDigit(text.charAt(index))) { index++; digits++; }
                if (digits == 0) throw error("invalid number");
            }
            if (!end() && (text.charAt(index) == '.' || text.charAt(index) == 'e'
                    || text.charAt(index) == 'E' || text.charAt(index) == '+')) {
                throw error("only integer JSON numbers are accepted");
            }
            try { return Long.parseLong(text.substring(start, index)); }
            catch (NumberFormatException failure) { throw error("integer is out of range"); }
        }
        private boolean consume(char expected) {
            if (!end() && text.charAt(index) == expected) { index++; return true; }
            return false;
        }
        private void require(char expected) {
            if (!consume(expected)) throw error("expected '" + expected + "'");
        }
    }

    private static final class Writer {
        private static void write(Object value, StringBuilder output, int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting is too deep");
            if (value == null) { output.append("null"); return; }
            if (value instanceof String text) { string(text, output); return; }
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                output.append(value); return;
            }
            if (value instanceof Map<?, ?> map) {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object key is not a string");
                    }
                    if (!first) output.append(',');
                    first = false;
                    string(key, output);
                    output.append(':');
                    write(entry.getValue(), output, depth + 1);
                }
                output.append('}');
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                output.append('[');
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) output.append(',');
                    first = false;
                    write(item, output, depth + 1);
                }
                output.append(']');
                return;
            }
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
        }
        private static void string(String text, StringBuilder output) {
            if (text.length() > MAX_STRING) throw new IllegalArgumentException("JSON string is too long");
            output.append('"');
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                switch (current) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (current < 0x20) output.append(String.format("\\u%04x", (int) current));
                        else output.append(current);
                    }
                }
            }
            output.append('"');
        }
    }
}
