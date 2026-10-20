package org.sqlgen.verify;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Лёгкая модель схемы (таблица -> колонки) для офлайн-верификации.
 * Загружается из SQL-DDL без подключения к БД (подход C из ADR-001).
 */
public final class SchemaModel {
    private final Map<String, Set<String>> tables = new LinkedHashMap<>();

    public void addTable(String name) {
        tables.putIfAbsent(norm(name), new LinkedHashSet<>());
    }

    public void addColumn(String table, String column) {
        tables.computeIfAbsent(norm(table), k -> new LinkedHashSet<>()).add(norm(column));
    }

    public boolean hasTable(String name)          { return tables.containsKey(norm(name)); }
    public boolean hasColumn(String t, String c)  {
        Set<String> cols = tables.get(norm(t));
        return cols != null && cols.contains(norm(c));
    }
    public Set<String> tableNames() { return tables.keySet(); }

    static String norm(String s) { return s.toLowerCase().replace("\"", "").trim(); }

    /** Парсит CREATE TABLE ... (...) блоки из DDL-текста (эвристически, без зависимостей). */
    public static SchemaModel fromDdl(String ddl) {
        SchemaModel m = new SchemaModel();
        var matcher = java.util.regex.Pattern
            .compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"]+)\\s*\\((.*?)\\n\\s*\\)")
            .matcher(ddl);
        while (matcher.find()) {
            String table = matcher.group(1).replaceAll(".*\\.", "");
            m.addTable(table);
            for (String rawLine : matcher.group(2).split(",(?![^()]*\\))")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                String upper = line.toUpperCase();
                if (upper.startsWith("PRIMARY") || upper.startsWith("FOREIGN") || upper.startsWith("UNIQUE")
                        || upper.startsWith("CHECK") || upper.startsWith("CONSTRAINT")) continue;
                String col = line.split("\\s+")[0];
                if (col.matches("[A-Za-z_][\\w]*")) m.addColumn(table, col);
            }
        }
        return m;
    }
}
