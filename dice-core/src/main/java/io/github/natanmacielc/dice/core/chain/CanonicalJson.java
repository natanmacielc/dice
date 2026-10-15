package io.github.natanmacielc.dice.core.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON canônico da chain-v1 (subconjunto do RFC 8785): objetos com chaves ordenadas, arrays,
 * strings e inteiros em ±(2^53 - 1). Sem espaços. Ver spec/ALGORITHM.md, seção 6.3.
 */
public final class CanonicalJson {

    public static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(normalize(value), out);
        return out.toString();
    }

    /**
     * Converte uma árvore JSON genérica para a forma usada aqui: Map com chaves String (ordem
     * preservada), List, String e Long. Lança IllegalArgumentException para qualquer outro tipo.
     */
    public static Object normalize(Object value) {
        if (value instanceof String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    throw new IllegalArgumentException("string com surrogate sem par");
                }
            }
            return s;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            long n = ((Number) value).longValue();
            if (Math.abs(n) > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("inteiro fora de ±(2^53 - 1): " + n);
            }
            return n;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new IllegalArgumentException("chave não-string: " + e.getKey());
                }
                out.put((String) normalize(k), normalize(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(normalize(o));
            }
            return Collections.unmodifiableList(out);
        }
        throw new IllegalArgumentException("tipo não permitido no JSON canônico: "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static void write(Object v, StringBuilder out) {
        if (v instanceof String s) {
            writeString(s, out);
        } else if (v instanceof Long n) {
            out.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            m.keySet().forEach(k -> keys.add((String) k));
            Collections.sort(keys); // String.compareTo compara unidades UTF-16, como o JCS
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeString(keys.get(i), out);
                out.append(':');
                write(m.get(keys.get(i)), out);
            }
            out.append('}');
        } else if (v instanceof List<?> l) {
            out.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(l.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalStateException("valor não normalizado: " + v);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
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
}
