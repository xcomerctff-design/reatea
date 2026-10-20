package org.sqlgen.parser;

import org.sqlgen.ast.Procedure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Минимальный JSON-парсер спецификации процедуры из редактора.
 * Без внешних зависимостей (в продакшене заменяется на Jackson/Gson).
 * Ожидает формат:
 * {"name":"...","dialect":"postgresql","params":[{"name":"p_x","type":"BIGINT"}],
 *  "steps":[{"kind":"sql","id":"s1","line":3,"sql":"..."}, ...]}
 */
public final class ProcedureJsonParser {

    public static class ParseError extends RuntimeException {
        public final int line, col;
        public ParseError(String msg, int line, int col) { super(msg); this.line = line; this.col = col; }
    }

    private final String src;
    private int pos;

    private ProcedureJsonParser(String src) { this.src = src; }

    public static Procedure parse(String json) {
        ProcedureJsonParser p = new ProcedureJsonParser(json);
        p.skipWs();
        Object root = p.readValue();
        if (!(root instanceof JsonObj)) throw new ParseError("root must be an object", 1, 1);
        return p.toProcedure((JsonObj) root);
    }

    @SuppressWarnings("unchecked")
    private Procedure toProcedure(JsonObj o) {
        Procedure proc = new Procedure();
        proc.name = o.str("name");
        String d = o.strOr("dialect", "postgresql");
        proc.dialect = "mysql".equalsIgnoreCase(d) ? Procedure.Dialect.MYSQL : Procedure.Dialect.POSTGRESQL;
        for (Object po : o.arrOr("params", new java.util.ArrayList<>())) {
            JsonObj p = (JsonObj) po;
            proc.params.add(new Procedure.Param(p.str("name"), p.str("type")));
        }
        for (Object so : o.arrOr("steps", new java.util.ArrayList<>())) {
            JsonObj s = (JsonObj) so;
            String kind = s.strOr("kind", "sql");
            Procedure.Step step;
            switch (kind) {
                // Ключи читаем в двух регистрах: camelCase (UI/TypeScript) и snake_case (serde по умолчанию в Rust).
                case "guard":  { var g = new Procedure.GuardStep();  g.existsQuery = s.strOr2("existsQuery", "exists_query", ""); g.message = s.strOr("message","guard failed"); step = g; } break;
                case "return": { var r = new Procedure.ReturnStep(); r.selectSql = s.strOr2("selectSql", "select_sql", ""); step = r; } break;
                case "assign": { var a = new Procedure.AssignStep(); a.variable = s.str("variable"); a.expression = s.str("expression"); step = a; } break;
                default:       { var q = new Procedure.SqlStep();    q.sql = s.strOr("sql",""); step = q; } break;
            }
            step.id = s.strOr("id", "step");
            step.lineHint = (int)(long) s.numOr("line", 0);
            proc.steps.add(step);
        }
        if (proc.name == null || proc.name.isBlank())
            throw new ParseError("procedure name is required", 1, 1);
        return proc;
    }

    // ---------- микро-токенайзер JSON ----------
    private void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw err("unexpected end of input");
        char c = src.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBool();
        if (c == 'n') { expectLit("null"); return null; }
        return readNumber();
    }

    private JsonObj readObject() {
        JsonObj o = new JsonObj();
        pos++; // {
        skipWs();
        if (peek() == '}') { pos++; return o; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            if (peek() != ':') throw err("expected ':'");
            pos++;
            o.map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return o; }
            throw err("expected ',' or '}'");
        }
    }

    private java.util.List<Object> readArray() {
        java.util.List<Object> list = new java.util.ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return list; }
            throw err("expected ',' or ']'");
        }
    }

    private String readString() {
        if (peek() != '"') throw err("expected string");
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u': sb.append((char) Integer.parseInt(src.substring(pos, pos+4), 16)); pos += 4; break;
                    default: throw err("bad escape \\" + e);
                }
            } else sb.append(c);
        }
        throw err("unterminated string");
    }

    private Boolean readBool() {
        if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
        if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        throw err("bad literal");
    }

    private void expectLit(String lit) {
        if (!src.startsWith(lit, pos)) throw err("expected " + lit);
        pos += lit.length();
    }

    private Long readNumber() {
        Matcher m = Pattern.compile("-?\\d+").matcher(src.substring(pos));
        if (!m.find() || m.start() != 0) throw err("expected number");
        pos += m.group().length();
        return Long.parseLong(m.group());
    }

    private char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }

    private ParseError err(String msg) {
        int line = 1, lastNl = -1;
        for (int i = 0; i < Math.min(pos, src.length()); i++)
            if (src.charAt(i) == '\n') { line++; lastNl = i; }
        return new ParseError(msg, line, pos - lastNl);
    }

    /** Простая модель объекта. */
    public static final class JsonObj {
        final java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        public String str(String k)      { Object v = map.get(k); return v == null ? null : v.toString(); }
        public String strOr(String k,String d){ Object v = map.get(k); return v == null ? d : v.toString(); }
        /** Значение по первому непустому ключу — поддержка camelCase и snake_case одновременно. */
        public String strOr2(String k1, String k2, String d) {
            Object v = map.get(k1);
            if (v == null) v = map.get(k2);
            return v == null ? d : v.toString();
        }
        public long   numOr(String k,long d)  { Object v = map.get(k); return v == null ? d : (long)(Long)v; }
        @SuppressWarnings("unchecked")
        public java.util.List<Object> arrOr(String k, java.util.List<Object> d) {
            Object v = map.get(k); return v == null ? d : (java.util.List<Object>) v;
        }
    }
}
