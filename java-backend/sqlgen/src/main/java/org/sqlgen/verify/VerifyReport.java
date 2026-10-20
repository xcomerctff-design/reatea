package org.sqlgen.verify;

import java.util.ArrayList;
import java.util.List;

/** Структурированный отчёт проверки — формат обмена Rust <-> Java. */
public final class VerifyReport {

    public enum Severity { ERROR, WARNING, INFO }

    public static final class Issue {
        public final int stepIndex, lineHint;
        public final Severity severity;
        public final String rule, message;

        public Issue(int stepIndex, int lineHint, Severity sev, String rule, String message) {
            this.stepIndex = stepIndex; this.lineHint = lineHint;
            this.severity = sev; this.rule = rule; this.message = message;
        }
    }

    public final List<Issue> issues = new ArrayList<>();
    public String generatedSql;   // заполняется после успешной валидации AST

    public boolean isValid() {
        return issues.stream().noneMatch(i -> i.severity == Severity.ERROR);
    }

    public void error(int idx, int line, String rule, String msg)   { issues.add(new Issue(idx, line, Severity.ERROR, rule, msg)); }
    public void warn(int idx, int line, String rule, String msg)    { issues.add(new Issue(idx, line, Severity.WARNING, rule, msg)); }

    /** Сериализация в JSON для отправки в редактор (Tauri-команда вернёт эту строку). */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"valid\":").append(isValid()).append(",\"errors\":[");
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"stepIndex\":").append(it.stepIndex)
              .append(",\"line\":").append(it.lineHint)
              .append(",\"severity\":\"").append(it.severity)
              .append("\",\"rule\":\"").append(it.rule)
              .append("\",\"message\":\"").append(esc(it.message)).append("\"}");
        }
        sb.append("]");
        if (generatedSql != null)
            sb.append(",\"generatedSql\":\"").append(esc(generatedSql)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
