package org.sqlgen.verify;

import org.sqlgen.ast.Procedure;
import org.sqlgen.codegen.CodeGenerator;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Верификатор процедур (ядро подхода C из ADR-001).
 * Работает офлайн: статические правила + проверка ссылок на схему из DDL-модели.
 * Опционально (если передан DsnChecker) — dry-run EXPLAIN на тестовой БД (подход B как опция).
 */
public final class Verifier {

    private static final Pattern IDENT = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\.([a-zA-Z_][\\w]*)\\b");
    private static final Pattern FROM_JOIN = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([a-zA-Z_][\\w]*)");
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "select","insert","update","delete","from","where","join","left","right","inner","outer",
        "on","and","or","not","null","is","in","exists","between","like","order","by","group",
        "having","limit","offset","values","into","set","as","distinct","count","sum","avg","min",
        "max","case","when","then","else","end","asc","desc","returning","now","true","false");

    public interface DsnChecker {                 // необязательный раунд-трип к тестовой БД
        void apply(Procedure p, VerifyReport r);
    }

    private final SchemaModel schema;
    private final DsnChecker dsnChecker;         // nullable

    public Verifier(SchemaModel schema) { this(schema, null); }
    public Verifier(SchemaModel schema, DsnChecker dsnChecker) {
        this.schema = schema;
        this.dsnChecker = dsnChecker;
    }

    public VerifyReport verify(Procedure p) {
        VerifyReport r = new VerifyReport();

        // правило 1: имя процедуры
        if (p.name == null || !p.name.matches("[a-zA-Z_][\\w]{1,62}"))
            r.error(-1, 0, "NAME", "Имя процедуры должно быть валидным идентификатором (a-z_, 3..63 символа)");

        // правило 2: параметры
        for (Procedure.Param param : p.params) {
            if (param.name == null || param.type == null || param.type.isBlank())
                r.error(-1, 0, "PARAM", "Параметр '" + param.name + "' без типа");
        }

        // правило 3: шаги непустые
        if (p.steps.isEmpty())
            r.warn(-1, 0, "EMPTY", "Процедура не содержит шагов");

        // правило 4..7: по каждому шагу
        int idx = 0;
        for (Procedure.Step s : p.steps) {
            String sql = extractSql(s);
            checkBalanced(idx, s, sql, r);
            checkDanglingRefs(idx, s, sql, r);
            checkTableRefs(idx, s, sql, r);
            checkColumnRefs(idx, s, sql, p, r);
            idx++;
        }

        // правило 8: генерируем код только если нет ошибок — это и есть «единый источник истины»
        if (r.isValid()) {
            r.generatedSql = CodeGenerator.forDialect(p.dialect).generate(p);
            if (dsnChecker != null) dsnChecker.apply(p, r);   // опциональный EXPLAIN dry-run
        }
        return r;
    }

    private static String extractSql(Procedure.Step s) {
        if (s instanceof Procedure.SqlStep q)      return nullToEmpty(q.sql);
        if (s instanceof Procedure.GuardStep g)    return nullToEmpty(g.existsQuery);
        if (s instanceof Procedure.ReturnStep rr)  return nullToEmpty(rr.selectSql);
        if (s instanceof Procedure.AssignStep a)   return nullToEmpty(a.expression);
        return "";
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static void checkBalanced(int idx, Procedure.Step s, String sql, VerifyReport r) {
        int open = count(sql, '('), close = count(sql, ')');
        if (open != close)
            r.error(idx, s.lineHint, "SYNTAX", "Разбалансированные скобки в шаге '" + s.id + "'");
        long quotes = sql.chars().filter(c -> c == '\'').count();
        if (quotes % 2 != 0)
            r.error(idx, s.lineHint, "SYNTAX", "Нечётное число одинарных кавычек в шаге '" + s.id + "'");
        if (sql.isBlank() && !(s instanceof Procedure.SqlStep))
            r.error(idx, s.lineHint, "EMPTY_STEP", "Пустой SQL в шаге '" + s.id + "'");
    }

    /** SELECT без FROM / висячие AND/OR. */
    private static void checkDanglingRefs(int idx, Procedure.Step s, String sql, VerifyReport r) {
        String low = sql.toLowerCase(Locale.ROOT);
        if (low.contains("select") && !low.contains("from") && !low.contains("select 1")
                && !low.matches("(?s).*select\\s+\\d+.*"))
            r.warn(idx, s.lineHint, "STYLE", "SELECT без FROM в шаге '" + s.id + "'");
        if (low.matches("(?s).*(where|and|or)\\s*(and|or)\\s*$") || low.endsWith(" where "))
            r.error(idx, s.lineHint, "SYNTAX", "Обрывок условия в конце шага '" + s.id + "'");
    }

    private void checkTableRefs(int idx, Procedure.Step s, String sql, VerifyReport r) {
        Matcher m = FROM_JOIN.matcher(sql);
        while (m.find()) {
            String t = m.group(1).toLowerCase(Locale.ROOT);
            if (SQL_KEYWORDS.contains(t)) continue;
            if (!schema.hasTable(t))
                r.error(idx, s.lineHint, "SCHEMA_TABLE", "Неизвестная таблица '" + t + "' (шаг '" + s.id + "')");
        }
    }

    /** qualified.column ссылки: alias должен резолвиться в таблицу схемы либо параметр/переменную. */
    private void checkColumnRefs(int idx, Procedure.Step s, String sql, Procedure p, VerifyReport r) {
        // алиасы: "FROM table alias" и "INTO var" допустимы как префиксы
        Matcher al = Pattern.compile("(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+[\\w\"]+\\s+(?:AS\\s+)?([a-zA-Z_]\\w*)")
                             .matcher(sql);
        java.util.Map<String,String> aliasToTable = new java.util.HashMap<>();
        Matcher ft = FROM_JOIN.matcher(sql);
        java.util.List<String> tables = new java.util.ArrayList<>();
        while (ft.find()) tables.add(ft.group(1));
        while (al.find()) {
            String a = al.group(1).toLowerCase(Locale.ROOT);
            if (!SQL_KEYWORDS.contains(a) && !tables.isEmpty())
                aliasToTable.put(a, tables.get(0));
        }

        Matcher m = IDENT.matcher(sql);
        while (m.find()) {
            String prefix = m.group(1).toLowerCase(Locale.ROOT);
            String col = m.group(2).toLowerCase(Locale.ROOT);
            boolean isParam = p.params.stream().anyMatch(x -> x.name != null && x.name.equalsIgnoreCase(prefix));
            boolean isVar = prefix.startsWith("v_") || prefix.startsWith("new.") ;
            if (isParam || isVar) continue;
            String table = schema.hasTable(prefix) ? prefix : aliasToTable.get(prefix);
            if (table == null) {
                r.error(idx, s.lineHint, "SCHEMA_ALIAS",
                        "Неизвестный префикс '" + prefix + "' в ссылке '" + prefix + "." + col + "'");
            } else if (!schema.hasColumn(table, col)) {
                r.error(idx, s.lineHint, "SCHEMA_COLUMN",
                        "Колонка '" + col + "' не найдена в таблице '" + table + "'");
            }
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == c) n++;
        return n;
    }
}
