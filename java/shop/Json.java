package shop;

import java.math.BigDecimal;
import java.util.*;

/** Минимальные сериализатор и парсер JSON (без внешних зависимостей). */
public final class Json {
    private Json() {}

    // ---------- запись ----------

    /** Превращает Map / List / String / число / Boolean / null в JSON-строку. */
    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof BigDecimal decimal) {
            builder.append(decimal.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean isFirstEntry = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!isFirstEntry) builder.append(',');
                isFirstEntry = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Collection<?> collection) {
            builder.append('[');
            boolean isFirstElement = true;
            for (Object element : collection) {
                if (!isFirstElement) builder.append(',');
                isFirstElement = false;
                writeValue(builder, element);
            }
            builder.append(']');
        } else {
            writeString(builder, value.toString());
        }
    }

    /** Записывает строку в кавычках, экранируя спецсимволы. */
    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            switch (symbol) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (symbol < 0x20) builder.append(String.format("\\u%04x", (int) symbol));
                    else builder.append(symbol);
                }
            }
        }
        builder.append('"');
    }

    // ---------- чтение ----------

    /** Возвращает Map / List / String / BigDecimal / Boolean / null. */
    public static Object parse(String jsonText) {
        Parser parser = new Parser(jsonText);
        parser.skipWhitespace();
        Object result = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != jsonText.length()) throw parser.error("лишние символы после JSON");
        return result;
    }

    private static final class Parser {
        final String text;   // разбираемый JSON-текст
        int position = 0;    // индекс текущего символа в тексте

        Parser(String text) {
            this.text = text;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Некорректный JSON (позиция " + position + "): " + message);
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        Object readValue() {
            skipWhitespace();
            if (position >= text.length()) throw error("неожиданный конец");
            char currentChar = text.charAt(position);
            if (currentChar == '{') return readObject();
            if (currentChar == '[') return readArray();
            if (currentChar == '"') return readString();
            if (text.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
            if (text.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
            if (text.startsWith("null", position)) { position += 4; return null; }
            return readNumber();
        }

        Map<String, Object> readObject() {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            position++; // пропускаем '{'
            skipWhitespace();
            if (position < text.length() && text.charAt(position) == '}') {
                position++;
                return resultMap;
            }
            while (true) {
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != '"') throw error("ожидалась строка-ключ");
                String key = readString();
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != ':') throw error("ожидалось ':'");
                position++; // пропускаем ':'
                resultMap.put(key, readValue());
                skipWhitespace();
                if (position >= text.length()) throw error("неожиданный конец");
                char separator = text.charAt(position++);
                if (separator == '}') return resultMap;
                if (separator != ',') throw error("ожидалось ',' или '}'");
            }
        }

        List<Object> readArray() {
            List<Object> resultList = new ArrayList<>();
            position++; // пропускаем '['
            skipWhitespace();
            if (position < text.length() && text.charAt(position) == ']') {
                position++;
                return resultList;
            }
            while (true) {
                resultList.add(readValue());
                skipWhitespace();
                if (position >= text.length()) throw error("неожиданный конец");
                char separator = text.charAt(position++);
                if (separator == ']') return resultList;
                if (separator != ',') throw error("ожидалось ',' или ']'");
            }
        }

        String readString() {
            StringBuilder content = new StringBuilder();
            position++; // пропускаем открывающую кавычку
            while (position < text.length()) {
                char currentChar = text.charAt(position++);
                if (currentChar == '"') return content.toString();
                if (currentChar == '\\') {
                    if (position >= text.length()) break;
                    char escapedChar = text.charAt(position++);
                    switch (escapedChar) {
                        case 'n' -> content.append('\n');
                        case 't' -> content.append('\t');
                        case 'r' -> content.append('\r');
                        case 'b' -> content.append('\b');
                        case 'f' -> content.append('\f');
                        case 'u' -> {
                            if (position + 4 > text.length()) throw error("плохая \\u-последовательность");
                            content.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> content.append(escapedChar);
                    }
                } else {
                    content.append(currentChar);
                }
            }
            throw error("строка не закрыта");
        }

        BigDecimal readNumber() {
            int startPosition = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            if (startPosition == position) throw error("неожиданный символ '" + text.charAt(position) + "'");
            try {
                return new BigDecimal(text.substring(startPosition, position));
            } catch (NumberFormatException e) {
                throw error("плохое число");
            }
        }
    }
}
