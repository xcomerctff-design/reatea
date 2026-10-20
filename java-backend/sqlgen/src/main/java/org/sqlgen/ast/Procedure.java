package org.sqlgen.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Корневой узел визуальной процедуры, приходящей из редактора (JSON).
 * Procedure -> name, dialect, params[], steps[].
 */
public class Procedure {
    public enum Dialect { POSTGRESQL, MYSQL }

    public static class Param {
        public String name;
        public String type;      // BIGINT, TEXT, NUMERIC(12,2)...
        public Param() {}
        public Param(String name, String type) { this.name = name; this.type = type; }
    }

    public String name;
    public Dialect dialect = Dialect.POSTGRESQL;
    public List<Param> params = new ArrayList<>();
    public List<Step> steps = new ArrayList<>();

    /** Один шаг визуального графа. */
    public static abstract class Step {
        public String id;
        public int lineHint;                 // позиция в UI для маппинга ошибок
        public abstract String kind();
    }

    /** Произвольный SQL-шаг, вставляемый как есть. */
    public static class SqlStep extends Step {
        public String sql;
        @Override public String kind() { return "sql"; }
    }

    /** Шаг IF NOT EXISTS(query) THEN RAISE message. */
    public static class GuardStep extends Step {
        public String existsQuery;
        public String message;
        @Override public String kind() { return "guard"; }
    }

    /** Шаг RETURN QUERY select. */
    public static class ReturnStep extends Step {
        public String selectSql;
        @Override public String kind() { return "return"; }
    }

    /** Шаг присваивания переменной: v := expr. */
    public static class AssignStep extends Step {
        public String variable;
        public String expression;
        @Override public String kind() { return "assign"; }
    }
}
