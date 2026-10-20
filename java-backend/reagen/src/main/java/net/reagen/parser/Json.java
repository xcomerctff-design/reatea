package net.reagen.parser;

/**
 * Минимальный JSON-парсер (без внешних зависимостей), достаточный для формата процедур.
 * Поддерживает объекты, массивы, строки, числа, true/false/null.
 */
public final class Json {
    public static Object parse(String src) {
        P p = new P(src);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.eof()) throw new RuntimeException("Trailing garbage at " + p.pos);
        return v;
    }

    private static final class P {
        final String s; int pos;
        P(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        char c() { return s.charAt(pos); }
        void ws() { while (!eof() && Character.isWhitespace(c())) pos++; }
        void expect(char ch) { if (eof() || c() != ch) throw err("expected " + ch); pos++; }

        Object value() {
            ws();
            if (eof()) throw err("unexpected end");
            return switch (c()) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> num();
            };
        }

        java.util.Map<String, Object> obj() {
            var m = new java.util.LinkedHashMap<String, Object>();
            expect('{'); ws();
            if (c() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = str();
                ws(); expect(':');
                m.put(k, value());
                ws();
                if (c() == ',') { pos++; continue; }
                expect('}'); return m;
            }
        }

        java.util.List<Object> arr() {
            var l = new java.util.ArrayList<>();
            expect('['); ws();
            if (c() == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (c() == ',') { pos++; continue; }
                expect(']'); return l;
            }
        }

        String str() {
            expect('"');
            var sb = new StringBuilder();
            while (c() != '"') {
                char ch = c();
                if (ch == '\\') {
                    pos++;
                    switch (c()) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        case '/' -> sb.append('/');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos + 1, pos + 5), 16)); pos += 4; }
                        default -> sb.append(c());
                    }
                } else sb.append(ch);
                pos++;
            }
            pos++;
            return sb.toString();
        }

        Object num() {
            int start = pos;
            if (c() == '-') pos++;
            while (!eof() && (Character.isDigit(c()) || c() == '.' || c() == 'e' || c() == 'E' || c() == '+' || c() == '-')) pos++;
            String t = s.substring(start, pos);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            long l = Long.parseLong(t);
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
        }

        Object lit(String word, Object val) {
            if (!s.startsWith(word, pos)) throw err("expected " + word);
            pos += word.length();
            return val;
        }

        RuntimeException err(String msg) { return new RuntimeException(msg + " at offset " + pos); }
    }

    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> obj(Object o) { return (java.util.Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static java.util.List<Object> arr(Object o) { return (java.util.List<Object>) o; }
    public static String str(Object o) { return (String) o; }
    public static double dbl(Object o) { return ((Number) o).doubleValue(); }
}
