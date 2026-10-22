package orderbook.demo;

import java.util.List;
import java.util.OptionalLong;

/** Minimal JSON writer; values passed to {@link #obj} and {@link #array} are already-encoded JSON. */
final class Json {

    private Json() {
    }

    static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String num(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "null";
    }

    static String array(List<String> values) {
        return "[" + String.join(",", values) + "]";
    }

    static String obj(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(str(keyValues[i])).append(':').append(keyValues[i + 1]);
        }
        return sb.append('}').toString();
    }
}
